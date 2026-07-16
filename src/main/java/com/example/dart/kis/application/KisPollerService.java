package com.example.dart.kis.application;

import com.example.dart.common.infra.MarketCalendar;
import com.example.dart.common.infra.PollBackoff;
import com.example.dart.common.infra.PollWorker;
import com.example.dart.config.AppConfig;
import com.example.dart.kis.domain.Session;
import com.example.dart.kis.domain.VolumeRankItem;
import com.example.dart.kis.infra.DomesticMarketClient;
import com.example.dart.kis.infra.KisClient;
import com.example.dart.kis.infra.SectorCacheStore;
import com.example.dart.kis.infra.UsFuturesClient;
import com.example.dart.llm.LlmClient;
import com.example.dart.news.NewsHeadlineBuffer;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * KIS 시장분석 컨텍스트의 스케줄러 파사드 — 폴링 루프·주기 스케줄만 잡고 실제 일은 협력자에 위임한다:
 * 급등 정찰(GainerScout), 섹터 요약(SectorSummaryService), 거래대금 랭킹(TurnoverRankingService),
 * 수급 랭킹(InvestorFlowService), 시황 매크로 분석(MacroReportService).
 *
 * 협력자 조립은 이 파사드가 담당한다(컨텍스트 내부 배선) — 외부 IO 클라이언트(KIS·미선물·국내지수)는
 * 여전히 App에서 주입받는다.
 */
public class KisPollerService {

    private static final Logger log = LoggerFactory.getLogger(KisPollerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 재시작 후 첫 시황분석 발송 지연(초). 인메모리 뉴스 버퍼는 재시작 시 비므로, 뉴스 폴러
     * (RSS 0s·네이버 5s·구글 10s)가 한 바퀴 돌아 버퍼를 채울 시간을 준 뒤 첫 리포트를 보낸다
     * — 그래야 첫 발송에도 '오늘 촉매'·주도 테마가 실린다. 이후 발송은 정상 주기.
     */
    private static final long MACRO_FIRST_RUN_DELAY_SEC = 45;

    private final KisClient client;
    /** 거래일 판정(주말·공휴일 게이트). 폴러가 휴장일에 stale 데이터로 동작하지 않게 한다. */
    private final MarketCalendar calendar;
    private final int intervalSec;
    private final double minChangePct;
    /** 섹터 요약 주기(분). 0이면 섹터 요약 비활성. */
    private final int sectorSummaryMin;
    /** 장중 외국인·기관 수급 랭킹 주기(분). 0이면 비활성. */
    private final int investorFlowMin;
    /** 코스피·코스닥 '시장 전체' 외국인·기관 순매수 헤드라인 주기(분). 0이면 비활성. */
    private final int marketFlowMin;
    /** 급등(개별 종목) 알림 활성 여부. false면 급등 알림을 보내지 않는다(수급 랭킹만 운용 등). */
    private final boolean gainerAlertEnabled;
    /** 장 흐름 분석 리포트용 LLM(Gemini/Ollama). null이면 리포트 비활성. */
    private final LlmClient llm;
    /** 시황 매크로 분석 주기(분). 0이면 비활성. 검색 그라운딩이 호출당 과금이라 10분이 아닌 시간당 운영. */
    private final int reportIntervalMin;
    /** 시황 분석에 실시간 검색 그라운딩을 켤지 — 시작 로그 표기용(실사용은 MacroReportService). */
    private final boolean reportGrounding;
    private final NewsHeadlineBuffer headlineBuffer;

    private final GainerScout gainerScout;
    private final SectorSummaryService sectorSummary;
    private final TurnoverRankingService turnoverRanking;
    private final InvestorFlowService investorFlow;
    private final MacroReportService macroReport;

    private final PollWorker scheduler = new PollWorker("kis-poller");
    private final PollBackoff backoff = new PollBackoff();

    /** 직전 폴링 시점의 장 개장 여부 — 개장→마감 전이를 감지해 마감 요약을 1회 보낸다. */
    private volatile boolean wasOpen = false;
    /** 직전에 개장 중이던 세션 — 마감 요약을 그 세션 시장구분으로 라이브 조회하기 위해 기억. */
    private volatile Session lastSession;

    public KisPollerService(KisClient client, Notifier notifier, Notifier reportNotifier,
                            KisAlertComposer alertComposer, AppConfig.KisConfig config, Path sectorsFile,
                            LlmClient llm, MarketCalendar calendar, NewsHeadlineBuffer headlineBuffer,
                            UsFuturesClient usFutures, DomesticMarketClient domesticMarket) {
        this.client = client;
        this.calendar = calendar;
        this.headlineBuffer = headlineBuffer;
        this.intervalSec = config.pollIntervalSec();
        this.minChangePct = config.minChangePct();
        this.sectorSummaryMin = config.sectorSummaryMin();
        this.investorFlowMin = config.investorFlowMin();
        this.marketFlowMin = config.marketFlowMin();
        this.gainerAlertEnabled = config.gainerAlertEnabled();
        // 리포트는 LLM이 주입되고 활성 설정일 때만 동작(둘 중 하나라도 없으면 비활성).
        this.llm = config.marketReportEnabled() ? llm : null;
        this.reportIntervalMin = config.marketReportIntervalMin();   // 시황 매크로 분석 주기(시간당)
        this.reportGrounding = config.marketReportGrounding();       // 실시간 검색 그라운딩 on/off

        // 컨텍스트 내부 협력자 조립 — 외부 IO 클라이언트는 전부 주입분을 넘긴다.
        SectorCacheStore sectors = new SectorCacheStore(client, sectorsFile);
        this.gainerScout = new GainerScout(notifier, alertComposer, minChangePct,
                Duration.ofMinutes(config.cooldownMin()));
        this.sectorSummary = new SectorSummaryService(client, sectors, notifier, minChangePct);
        this.turnoverRanking = new TurnoverRankingService(client, sectors, notifier);
        this.investorFlow = new InvestorFlowService(client, notifier, domesticMarket, calendar,
                investorFlowMin > 0);
        this.macroReport = new MacroReportService(this.llm, config.marketReportGrounding(), reportNotifier,
                client, sectors, headlineBuffer, usFutures, domesticMarket,
                turnoverRanking, investorFlow, calendar, reportIntervalMin);
    }

    public void start() {
        log.info("KIS 급등 폴링 시작 (정규장 J 09:00~15:40 → NXT 애프터마켓 NX 15:40~20:00 KST, 주기 {}초, 임계 등락률≥{}%)",
                intervalSec, minChangePct);
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSec);

        if (sectorSummaryMin > 0) {
            long periodSec = sectorSummaryMin * 60L;
            // 벽시계 경계(예: 30분이면 매시 :00·:30)에 맞춰 첫 발송 시각을 정렬한다.
            long initialDelaySec = periodSec - (Instant.now().getEpochSecond() % periodSec);
            scheduler.scheduleWithFixedDelay(this::summarize, initialDelaySec, periodSec);
            log.info("KIS 섹터 요약 활성 ({}분 주기, 첫 발송 {}초 후)", sectorSummaryMin, initialDelaySec);
        }

        if (investorFlowMin > 0) {
            long periodSec = investorFlowMin * 60L;
            // 재시작 즉시 1회 발송(initialDelay=0)한 뒤 주기 반복 — 벽시계 정렬을 두지 않는다. (수급 표만, 분석은 별도 스케줄)
            scheduler.scheduleWithFixedDelay(investorFlow::tick, 0, periodSec);
            log.info("KIS 외국인·기관 수급 랭킹 활성 ({}분 주기, 재시작 즉시 발송)", investorFlowMin);
        }

        // 시장 전체 외국인·기관 순매수 헤드라인 — N분 주기로 KIS 채널에 한 줄 발송.
        if (marketFlowMin > 0) {
            long periodSec = marketFlowMin * 60L;
            scheduler.scheduleWithFixedDelay(investorFlow::marketFlowTick, 0, periodSec);
            log.info("KIS 시장 전체 수급 헤드라인 활성 ({}분 주기, 재시작 즉시 발송)", marketFlowMin);
        }

        // 시황 매크로 분석(시간당) — 실시간 검색 그라운딩으로 "지금 왜?"(미선물 이유·경제 일정·시황)를 뉴스 채널로.
        // 10분이 아니라 시간당인 이유: 그라운딩이 호출당 과금이고 매크로 맥락은 10분마다 안 바뀐다.
        if (llm != null && reportIntervalMin > 0) {
            long periodSec = reportIntervalMin * 60L;
            // 첫 발송은 뉴스 폴러가 버퍼를 채울 시간을 준 뒤 1회(장중이면 발송, 장외면 게이트로 스킵) 후 주기 반복.
            // 버퍼가 없으면(뉴스 비활성) 지연 없이 즉시. 이후 발송은 정상 주기.
            long firstDelaySec = headlineBuffer != null ? MACRO_FIRST_RUN_DELAY_SEC : 0;
            scheduler.scheduleWithFixedDelay(macroReport::generate, firstDelaySec, periodSec);
            log.info("🌐 시황 매크로 분석 활성 ({}분 주기, 모델 {}, 그라운딩 {}, 첫 발송 {}초 후)",
                    reportIntervalMin, llm.model(), reportGrounding ? "ON(검색)" : "OFF(평문)", firstDelaySec);
        }

        // 시작 시 LLM(Gemini/Ollama) 연결 1회 자가진단 — 장외에도 키·연결 정상 여부를 즉시 로그로 확인한다.
        // (실제 장 흐름 분석 발송은 장중에만. 이 진단은 발송과 무관하게 연결만 확인.)
        if (llm != null) {
            scheduler.schedule(macroReport::llmSelfTest, 0);
        }
    }

    public void stop() {
        scheduler.stop();
        log.info("KIS 급등 폴링 중지 완료");
    }

    private void poll() {
        if (backoff.shouldSkip()) return;
        ZonedDateTime now = ZonedDateTime.now(KST);
        Session sess = currentSession(now);
        maybeFinalSummary(now, sess);  // 개장→마감 전이면 마감 요약 1건
        if (sess == null) return;
        lastSession = sess;  // 마감 요약이 직전 세션 시장구분으로 라이브 조회할 수 있게 기억

        if (!gainerAlertEnabled) return;  // 급등 알림 비활성 — 라이브 조회·발송 생략(수급 랭킹만 운용)

        List<VolumeRankItem> items;
        try {
            items = client.topGainers(sess.marketDiv);  // 정규장 J / NXT 애프터마켓 NX — 시각에 맞춰 자동 전환
            backoff.success();
        } catch (Exception e) {
            int skips = backoff.failure();
            log.warn("KIS 조회 실패 ({}연속) — {}회 폴링 건너뜀: {}",
                    backoff.consecutiveFailures(), skips, e.toString());
            return;
        }
        gainerScout.alert(items, sess.label, now.toInstant());
    }

    /** 주기 요약(10분) — 장중에만. 그 시점 라이브 급등 종목을 조회해 업종별로 집계·발송. */
    private void summarize() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        Session sess = currentSession(now);
        if (sess == null) return;
        LocalTime time = now.toLocalTime();
        sectorSummary.send(sess, sess.label, time);
        turnoverRanking.send(sess, sess.label, time);
    }

    /**
     * 개장→마감 전이를 감지해 마감 시점 라이브 조회로 최종 요약을 1회 보낸다.
     * 주기 요약 경계가 마감 시각과 어긋나도 그날의 마지막 그림을 남긴다. (직전 세션 시장구분으로 조회)
     */
    private void maybeFinalSummary(ZonedDateTime now, Session current) {
        if (wasOpen && current == null && lastSession != null) {
            log.info("장 마감 감지 — 마감 섹터 요약 발송");
            LocalTime time = now.toLocalTime();
            sectorSummary.send(lastSession, "🔔 장마감", time);
            turnoverRanking.send(lastSession, "🔔 장마감", time);
        }
        wasOpen = current != null;
    }

    /** 현재 시각의 시장 세션 — 주말·공휴일·운영시간 밖이면 null(폴링·요약 안 함). */
    private Session currentSession(ZonedDateTime now) {
        if (!calendar.isTradingDay(now.toLocalDate())) return null;
        return Session.at(now.toLocalTime());
    }
}
