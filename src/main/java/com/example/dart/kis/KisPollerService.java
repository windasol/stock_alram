package com.example.dart.kis;

import com.example.dart.config.AppConfig;
import com.example.dart.llm.LlmClient;
import com.example.dart.market.DomesticMarketClient;
import com.example.dart.market.UsFuturesClient;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * KIS 등락률순위를 주기적으로 스캔해 "오늘 많이 오른" 종목을 알린다 — 공시·뉴스 파이프라인의 보조 정찰망.
 *
 * 판단: 전일 종가 대비 등락률 ≥ 임계(기본 10%). 거래량·거래대금·주가 조건 없이 순수 등락률만 본다.
 *
 * 장중에만 폴링하고(주말·야간 제외), 같은 종목 반복 알림은 쿨다운으로 억제한다.
 */
public class KisPollerService {

    private static final Logger log = LoggerFactory.getLogger(KisPollerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 폴링 운영시간 — 현재 시각(KST)에 맞춰 시장구분을 자동 전환한다.
     *  - 정규장(KRX, 시장구분 J): 09:00~15:40 (마감 동시호가까지 KRX).
     *  - NXT 애프터마켓(NXT, 시장구분 NX): 15:40~20:00.
     * 이 시간 밖(야간·주말)엔 폴링하지 않는다. 두 구간은 15:40에서 맞닿아 폴링 공백이 없다.
     */
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 40);
    private static final LocalTime NXT_AFTER_CLOSE = LocalTime.of(20, 0);

    /** 현재 시각의 시장 세션 — 등락률순위 조회 시장구분(J/NX)과 알림 표시 라벨을 함께 든다. */
    enum Session {
        REGULAR("J", "정규장"),
        NXT_AFTER("NX", "🌆 NXT 애프터마켓");
        final String marketDiv;
        final String label;
        Session(String marketDiv, String label) {
            this.marketDiv = marketDiv;
            this.label = label;
        }
    }

    /** 시각이 속한 세션 — 운영시간 밖이면 null. 09:00~15:40 정규장(J), 15:40~20:00 NXT 애프터마켓(NX). (순수 함수 — 테스트용) */
    static Session sessionAt(LocalTime t) {
        if (!t.isBefore(REGULAR_OPEN) && t.isBefore(REGULAR_CLOSE)) return Session.REGULAR;
        if (!t.isBefore(REGULAR_CLOSE) && !t.isAfter(NXT_AFTER_CLOSE)) return Session.NXT_AFTER;
        return null;
    }

    private static final DateTimeFormatter SUMMARY_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    /** 업종 미상 종목의 섹터 라벨. */
    private static final String UNCLASSIFIED = "미분류";
    /** 거래대금 섹터 랭킹에서 보여줄 상위 섹터 수. */
    private static final int TURNOVER_TOP_SECTORS = 10;
    /** 각 섹터 안에서 보여줄 대표 종목 수(거래대금 상위). */
    private static final int TURNOVER_STOCKS_PER_SECTOR = 3;
    /** 외국인·기관 수급 랭킹에서 순매수/순매도 각각 보여줄 종목 수. */
    private static final int INVESTOR_FLOW_TOP = 30;
    /** 외국인+기관 동시매매(양매수/양매도)에서 각각 보여줄 종목 수. */
    private static final int INVESTOR_PAIR_TOP = 30;
    /**
     * 수급 단계 전환 시각(KST). 가집계(추정)는 09:30~14:30 입력으로 14:30이 마지막이라(KIS 공식),
     * 정규장 확정 집계 시각(KRX 15:35) 이후엔 가집계 대신 '확정치'(inquire-investor)로 그 날 1회 발송한다.
     *  - 09:00~15:35 : 가집계 추정 10분 폴링(표 + 분석)
     *  - 15:35~20:05 : KRX 확정 수급 1회(이후 NXT 라이브 수급은 데이터 소스가 없어 폴링하지 않음)
     *  - 20:05~      : NXT 최종 확정 집계 시각 — 최종 확정 1회, 이후 중단
     */
    private static final LocalTime KRX_CONFIRMED_AFTER = LocalTime.of(15, 35);
    private static final LocalTime NXT_CONFIRMED_AFTER = LocalTime.of(20, 5);
    /** KRX 정규장 확정 수급을 발송한 날짜(KST). 당일 재발송 방지 — 단일 폴러 스레드 접근이라 동기화 불필요. */
    private LocalDate krxConfirmedSentDate = null;
    /** NXT 최종 확정 수급을 발송한 날짜(KST). 당일 재발송 방지 — 단일 폴러 스레드 접근이라 동기화 불필요. */
    private LocalDate nxtConfirmedSentDate = null;

    private final KisClient client;
    private final Notifier notifier;
    /** 시황 분석(LLM) 전용 발송 채널 — 뉴스 채널. 표·급등·섹터는 {@link #notifier}(KIS 채널), 분석만 여기로. */
    private final Notifier reportNotifier;
    private final KisAlertComposer alertComposer;
    private final int intervalSec;
    private final double minChangePct;
    private final Duration cooldown;
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
    /** 시황 분석에 실시간 검색 그라운딩을 켤지. true면 chatGrounded(과금·실시간), false면 평문 chat. */
    private final boolean reportGrounding;
    /** 리포트 '대외 여건'용 미국 선물 조회기. 실패해도 국내 리포트는 계속된다. */
    private final UsFuturesClient usFutures = new UsFuturesClient();
    /** 리포트 '국내 지수(코스피·코스닥)·원달러 환율'용 조회기. 실패해도 국내 리포트는 계속된다. */
    private final DomesticMarketClient domesticMarket = new DomesticMarketClient();
    /** 시장 전체 수급(시세) 시장/업종 코드 — ⚠ 첫 호출 [시장수급진단] 로그로 검증 후 조정. */
    private static final String MARKET_FLOW_KOSPI_ISCD = "0001";    // 코스피 종합
    private static final String MARKET_FLOW_KOSDAQ_ISCD = "1001";   // 코스닥 종합
    private static final String MARKET_FLOW_ISCD2 = "0001";         // 업종구분(종합)
    /** 최신 시장 전체 수급 스냅샷 — 틱이 갱신하고 시황 리포트가 읽는다. */
    private volatile List<MarketInvestorFlow> lastMarketFlows = List.of();

    /** 종목코드 → 마지막 알림 시각. 쿨다운 내 재알림 억제. */
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();
    /**
     * 종목코드 → KRX 업종명 캐시. 업종은 거의 불변이라 한 번 조회하면 계속 재사용한다(디스크 영속화).
     * 모의 도메인 inquire-price는 유량제한이 빡빡해(≈초당 1~2건) 수십 건을 한꺼번에 조회하면 'EGW00201 초과'로
     * 빈 값→'미분류'가 되므로, 호출은 throttle하고 성공분은 캐시·파일에 모아 워밍업 1회 뒤엔 재조회를 없앤다.
     * (업종명은 정적이라 캐시해도 실시간 등락률과 무관 — 등락률은 요약 때마다 라이브로 새로 조회한다.)
     */
    private final Map<String, String> sectorCache = new ConcurrentHashMap<>();
    /** 직전 폴링 시점의 장 개장 여부 — 개장→마감 전이를 감지해 마감 요약을 1회 보낸다. */
    private volatile boolean wasOpen = false;
    /** 직전에 개장 중이던 세션 — 마감 요약을 그 세션 시장구분으로 라이브 조회하기 위해 기억. */
    private volatile Session lastSession;
    /** 업종 캐시 영속화 파일 — 업종은 거의 불변이라 한 번 조회분을 계속 재사용(유량제한 회피). 날짜 무관. */
    private final Path sectorsFile;
    /** 모의 도메인 inquire-price 유량제한 회피용 — 캐시 미스(실호출) 사이 최소 간격(ms). */
    private static final long SECTOR_LOOKUP_INTERVAL_MS = 1000L;
    private final ScheduledExecutorService scheduler;

    private int consecutiveFailures = 0;
    private int skipPolls = 0;

    public KisPollerService(KisClient client, Notifier notifier, Notifier reportNotifier,
                            KisAlertComposer alertComposer, AppConfig config, Path sectorsFile,
                            LlmClient llm) {
        this.client = client;
        this.notifier = notifier;
        this.reportNotifier = reportNotifier;
        this.alertComposer = alertComposer;
        this.intervalSec = config.kisPollIntervalSec();
        this.minChangePct = config.kisMinChangePct();
        this.cooldown = Duration.ofMinutes(config.kisCooldownMin());
        this.sectorSummaryMin = config.kisSectorSummaryMin();
        this.investorFlowMin = config.kisInvestorFlowMin();
        this.marketFlowMin = config.kisMarketFlowMin();
        this.gainerAlertEnabled = config.kisGainerAlertEnabled();
        this.sectorsFile = sectorsFile;
        // 리포트는 LLM이 주입되고 활성 설정일 때만 동작(둘 중 하나라도 없으면 비활성).
        this.llm = config.marketReportEnabled() ? llm : null;
        this.reportIntervalMin = config.marketReportIntervalMin();   // 시황 매크로 분석 주기(시간당)
        this.reportGrounding = config.marketReportGrounding();       // 실시간 검색 그라운딩 on/off
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "kis-poller"));
        loadSectors();
    }

    public void start() {
        log.info("KIS 급등 폴링 시작 (정규장 J 09:00~15:40 → NXT 애프터마켓 NX 15:40~20:00 KST, 주기 {}초, 임계 등락률≥{}%)",
                intervalSec, minChangePct);
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSec, TimeUnit.SECONDS);

        if (sectorSummaryMin > 0) {
            long periodSec = sectorSummaryMin * 60L;
            // 벽시계 경계(예: 30분이면 매시 :00·:30)에 맞춰 첫 발송 시각을 정렬한다.
            long initialDelaySec = periodSec - (Instant.now().getEpochSecond() % periodSec);
            scheduler.scheduleWithFixedDelay(this::summarize, initialDelaySec, periodSec, TimeUnit.SECONDS);
            log.info("KIS 섹터 요약 활성 ({}분 주기, 첫 발송 {}초 후)", sectorSummaryMin, initialDelaySec);
        }

        if (investorFlowMin > 0) {
            long periodSec = investorFlowMin * 60L;
            // 재시작 즉시 1회 발송(initialDelay=0)한 뒤 주기 반복 — 벽시계 정렬을 두지 않는다. (수급 표만, 분석은 별도 스케줄)
            scheduler.scheduleWithFixedDelay(this::investorFlowTick, 0, periodSec, TimeUnit.SECONDS);
            log.info("KIS 외국인·기관 수급 랭킹 활성 ({}분 주기, 재시작 즉시 발송)", investorFlowMin);
        }

        // 시장 전체(코스피·코스닥) 외국인·기관 순매수 헤드라인 — N분 주기로 KIS 채널에 한 줄 발송.
        if (marketFlowMin > 0) {
            long periodSec = marketFlowMin * 60L;
            scheduler.scheduleWithFixedDelay(this::marketFlowTick, 0, periodSec, TimeUnit.SECONDS);
            log.info("KIS 시장 전체 수급 헤드라인 활성 ({}분 주기, 재시작 즉시 발송)", marketFlowMin);
        }

        // 시황 매크로 분석(시간당) — 실시간 검색 그라운딩으로 "지금 왜?"(미선물 이유·경제 일정·시황)를 뉴스 채널로.
        // 10분이 아니라 시간당인 이유: 그라운딩이 호출당 과금이고 매크로 맥락은 10분마다 안 바뀐다.
        if (llm != null && reportIntervalMin > 0) {
            long periodSec = reportIntervalMin * 60L;
            // 재시작 즉시 1회(장중이면 발송, 장외면 게이트로 스킵) 후 주기 반복 — 바로 동작 확인 가능.
            scheduler.scheduleWithFixedDelay(this::generateMacroReport, 0, periodSec, TimeUnit.SECONDS);
            log.info("🌐 시황 매크로 분석 활성 ({}분 주기, 모델 {}, 그라운딩 {}, 재시작 즉시 1회)",
                    reportIntervalMin, llm.model(), reportGrounding ? "ON(검색)" : "OFF(평문)");
        }

        // 시작 시 LLM(Gemini/Ollama) 연결 1회 자가진단 — 장외에도 키·연결 정상 여부를 즉시 로그로 확인한다.
        // (실제 장 흐름 분석 발송은 장중에만. 이 진단은 발송과 무관하게 연결만 확인.)
        if (llm != null) {
            scheduler.schedule(this::llmSelfTest, 0, TimeUnit.SECONDS);
        }
    }

    /** 시작 시 LLM 연결을 1회 핑(ping)해 키·모델·네트워크 정상 여부를 로그로 남긴다. 분석 발송과 무관(장외에도 동작). */
    private void llmSelfTest() {
        try {
            String r = llm.chat("너는 연결 확인용이다. 군더더기 없이 'OK'만 답해.", "OK?");
            if (r != null && !r.isBlank()) {
                log.info("✅ LLM 연결 확인 OK ({}): \"{}\"", llm.model(), r.trim());
            } else {
                log.warn("⚠️ LLM 연결 확인 실패 ({}) — 응답 없음/차단. GEMINI_API_KEY·모델명·네트워크 확인", llm.model());
            }
        } catch (Exception e) {
            log.warn("⚠️ LLM 연결 확인 중 오류 ({}): {}", llm.model(), e.toString());
        }
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("KIS 급등 폴링 중지 완료");
    }

    private void poll() {
        if (skipPolls > 0) {
            skipPolls--;
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(KST);
        Session sess = currentSession(now);
        maybeFinalSummary(now, sess);  // 개장→마감 전이면 마감 요약 1건
        if (sess == null) return;
        lastSession = sess;  // 마감 요약이 직전 세션 시장구분으로 라이브 조회할 수 있게 기억

        if (!gainerAlertEnabled) return;  // 급등 알림 비활성 — 라이브 조회·발송 생략(수급 랭킹만 운용)

        List<VolumeRankItem> items;
        try {
            items = client.topGainers(sess.marketDiv);  // 정규장 J / NXT 애프터마켓 NX — 시각에 맞춰 자동 전환
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            skipPolls = Math.min(1 << consecutiveFailures, 40);
            log.warn("KIS 조회 실패 ({}연속) — {}회 폴링 건너뜀: {}",
                    consecutiveFailures, skipPolls, e.toString());
            return;
        }

        Instant nowInstant = now.toInstant();
        String session = sess.label;
        for (VolumeRankItem it : items) {
            if (!isBigGainer(it, minChangePct)) continue;
            if (!cooledDown(it.code(), nowInstant)) continue;
            lastAlert.put(it.code(), nowInstant);
            log.info("급등 [{} {} {}]: {}%", session, it.name(), it.code(), it.changePct());
            try {
                notifier.send(alertComposer.compose(it, session));
            } catch (Exception e) {
                log.warn("급등 알림 전송 실패: {} {}", it.name(), it.code(), e);
            }
        }
    }

    /** 업종 캐시를 파일에 저장한다 — 각 행: 코드\t업종명. 날짜 무관(업종은 안정적이라 계속 재사용). */
    private void persistSectors() {
        try {
            List<String> lines = new ArrayList<>(sectorCache.size());
            for (Map.Entry<String, String> e : sectorCache.entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue());
            }
            Files.write(sectorsFile, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("KIS 업종 캐시 저장 실패: {}", e.toString());
        }
    }

    /** 시작 시 업종 캐시를 복원한다 — 워밍업(수십 건 조회) 비용을 매 재시작마다 다시 치르지 않게. */
    private void loadSectors() {
        try {
            if (!Files.exists(sectorsFile)) return;
            for (String line : Files.readAllLines(sectorsFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 2);
                if (p.length == 2 && !p[1].isBlank()) sectorCache.put(p[0], p[1]);
            }
            log.info("KIS 업종 캐시 {}건 복원", sectorCache.size());
        } catch (Exception e) {
            log.warn("KIS 업종 캐시 복원 실패 — 빈 상태로 시작: {}", e.toString());
        }
    }

    /** 업종 실호출 사이 간격 — 모의 도메인 유량제한(EGW00201) 회피. */
    private void sleepBetweenLookups() {
        try {
            Thread.sleep(SECTOR_LOOKUP_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 종목코드들의 KRX 업종을 해소한다 — 캐시 우선, 미스만 throttle해서 라이브 조회 후 캐시·파일에 보존.
     * 조회 실패(빈 업종)는 캐시하지 않고(다음 주기 재시도) 결과 맵엔 '미분류'로 채운다.
     * 급등 요약·거래대금 랭킹이 공통으로 쓴다(업종 캐시·유량제한 회피 로직 단일화).
     */
    private Map<String, String> resolveSectors(List<String> codes) {
        Map<String, String> out = new HashMap<>();
        int newLookups = 0;
        for (String code : codes) {
            String s = sectorCache.get(code);
            if (s == null || s.isBlank()) {
                if (newLookups++ > 0) sleepBetweenLookups();  // 실호출 사이만 간격
                s = client.sectorOf(code);
                if (s != null && !s.isBlank()) sectorCache.put(code, s);  // 성공만 캐시
            }
            out.put(code, (s != null && !s.isBlank()) ? s : UNCLASSIFIED);
        }
        if (newLookups > 0) {
            persistSectors();  // 이번에 새로 조회된 업종을 파일에 보존(워밍업 1회로 끝)
            log.info("업종 신규조회 {}건", newLookups);
        }
        return out;
    }

    /** 주기 요약(10분) — 장중에만. 그 시점 라이브 급등 종목을 조회해 업종별로 집계·발송. */
    private void summarize() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        Session sess = currentSession(now);
        if (sess == null) return;
        sendSectorSummary(sess, sess.label, now.toLocalTime());
        sendTurnoverRanking(sess, sess.label, now.toLocalTime());
    }

    /**
     * 개장→마감 전이를 감지해 마감 시점 라이브 조회로 최종 요약을 1회 보낸다.
     * 주기 요약 경계가 마감 시각과 어긋나도 그날의 마지막 그림을 남긴다. (직전 세션 시장구분으로 조회)
     */
    private void maybeFinalSummary(ZonedDateTime now, Session current) {
        if (wasOpen && current == null && lastSession != null) {
            log.info("장 마감 감지 — 마감 섹터 요약 발송");
            sendSectorSummary(lastSession, "🔔 장마감", now.toLocalTime());
            sendTurnoverRanking(lastSession, "🔔 장마감", now.toLocalTime());
        }
        wasOpen = current != null;
    }

    /**
     * 이 시점의 라이브 급등 종목(현재 등락률)을 조회해 업종별로 집계, 섹터 요약 1건을 발송한다.
     * 등락률은 누적이 아니라 호출 시점 실시간 값이다. 업종명만 캐시(정적)를 쓴다. 급등 종목이 없으면 건너뛴다.
     */
    private void sendSectorSummary(Session session, String label, LocalTime time) {
        if (sectorSummaryMin <= 0) return;
        String msg = buildSectorSummary(session, label, time);
        if (msg == null) return;
        try {
            notifier.send(msg);
        } catch (Exception e) {
            log.warn("섹터 요약 알림 전송 실패", e);
        }
    }

    /**
     * 라이브 급등 종목을 업종별로 집계한 섹터 요약 문자열을 만든다(전송은 호출부 책임).
     * 급등 종목이 없거나 라이브 조회 실패면 null. 섹터 요약 발송과 장 흐름 리포트가 공통으로 쓴다.
     */
    private String buildSectorSummary(Session session, String label, LocalTime time) {
        List<VolumeRankItem> snapshot;
        try {
            snapshot = new ArrayList<>(client.topGainers(session.marketDiv));
        } catch (Exception e) {
            log.warn("섹터 요약 라이브 조회 실패 ({}): {}", label, e.toString());
            return null;
        }
        snapshot.removeIf(it -> !isBigGainer(it, minChangePct));  // 현재 ≥임계인 종목만
        if (snapshot.isEmpty()) {
            log.info("섹터 요약 건너뜀 — 급등 종목 없음 ({})", label);
            return null;
        }
        Map<String, String> sectors = resolveSectors(
                snapshot.stream().map(VolumeRankItem::code).toList());
        List<Gainer> resolved = new ArrayList<>(snapshot.size());
        int classified = 0;
        for (VolumeRankItem it : snapshot) {
            String s = sectors.getOrDefault(it.code(), UNCLASSIFIED);
            if (!UNCLASSIFIED.equals(s)) classified++;
            resolved.add(new Gainer(it.name(), s, it.changePct()));
        }
        log.info("섹터 요약 조립 ({}종목, 업종분류 {}/{}, {})",
                resolved.size(), classified, resolved.size(), label);
        if (classified == 0) {
            log.warn("섹터 업종 조회가 전부 실패 — KIS 업종(inquire-price) 응답/권한·유량제한 확인 필요");
        }
        return composeSectorSummary(resolved, label, time);
    }

    /**
     * 거래대금 상위 종목을 라이브 조회해 업종별 거래대금을 합산, "지금 어느 섹터가 활발한가"를
     * 거래대금 내림차순 섹터 랭킹 1건으로 발송한다(급등 섹터 요약과 별도 메시지).
     * 급등 종목이 없어도 거래대금은 장중 항상 존재하므로 독립적으로 동작한다.
     */
    private void sendTurnoverRanking(Session session, String label, LocalTime time) {
        if (sectorSummaryMin <= 0) return;
        String msg = buildTurnoverRanking(session, label, time);
        if (msg == null) return;
        try {
            notifier.send(msg);
        } catch (Exception e) {
            log.warn("거래대금 랭킹 알림 전송 실패", e);
        }
    }

    /**
     * 거래대금 상위 종목을 업종별로 합산한 거래대금 섹터 랭킹 문자열을 만든다(전송은 호출부 책임).
     * 종목이 없거나 라이브 조회 실패면 null. 거래대금 랭킹 발송과 장 흐름 리포트가 공통으로 쓴다.
     */
    private String buildTurnoverRanking(Session session, String label, LocalTime time) {
        List<TradingValueItem> snapshot;
        try {
            snapshot = client.topByTradingValue(session.marketDiv);
        } catch (Exception e) {
            log.warn("거래대금 랭킹 라이브 조회 실패 ({}): {}", label, e.toString());
            return null;
        }
        if (snapshot.isEmpty()) {
            log.info("거래대금 랭킹 건너뜀 — 종목 없음 ({})", label);
            return null;
        }
        Map<String, String> sectors = resolveSectors(
                snapshot.stream().map(TradingValueItem::code).toList());
        List<Turnover> resolved = new ArrayList<>(snapshot.size());
        for (TradingValueItem it : snapshot) {
            resolved.add(new Turnover(it.name(),
                    sectors.getOrDefault(it.code(), UNCLASSIFIED), it.tradingValueWon()));
        }
        log.info("거래대금 랭킹 조립 ({}종목, {})", resolved.size(), label);
        return composeTurnoverRanking(resolved, label, time);
    }

    /** 시황 분석 facts 블록에 넣을 외국인·기관 수급 상위 종목 수(토큰 절약). */
    private static final int FACTS_TOP = 10;

    /**
     * 시황 매크로 분석 — 국내 수급·미선물 '실측'을 근거로 깔고, Gemini 검색 그라운딩으로
     * "지금 왜?"(미국 선물 등락 이유·경제 일정·글로벌 시황)를 끌어와 국내 흐름과 엮어 뉴스 채널로 발송한다.
     * 모델이 컷오프 밖 사실을 지어내지 않도록 검색(그라운딩)으로만 외부 사실을 얻는다(하이브리드). 장중에만 동작.
     */
    private void generateMacroReport() {
        if (llm == null) return;
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return;
        Session sess = currentSession(now);
        if (sess == null) return;   // 장중에만
        LocalTime time = now.toLocalTime();

        // 국내 실측 facts(지지 근거) — 외국인은 외국계 실시간(J), 기관·양매수는 거래소 가집계.
        List<InvestorFlowItem> fb = client.foreignMemberEstimate("J", true).stream().limit(FACTS_TOP).toList();
        List<InvestorFlowItem> fs = client.foreignMemberEstimate("J", false).stream().limit(FACTS_TOP).toList();
        List<InvestorFlowItem> ib = client.investorFlowRank(KisClient.Investor.INSTITUTION, true).stream().limit(FACTS_TOP).toList();
        List<InvestorFlowItem> is = client.investorFlowRank(KisClient.Investor.INSTITUTION, false).stream().limit(FACTS_TOP).toList();
        List<InvestorPairItem> db = client.investorFlowDual(true).stream()
                .filter(it -> it.frgnWon() > 0 && it.orgnWon() > 0)
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon).reversed())
                .limit(FACTS_TOP).toList();

        List<String> codes = new ArrayList<>();
        for (InvestorFlowItem it : fb) codes.add(it.code());
        for (InvestorFlowItem it : fs) codes.add(it.code());
        for (InvestorFlowItem it : ib) codes.add(it.code());
        for (InvestorFlowItem it : is) codes.add(it.code());
        for (InvestorPairItem it : db) codes.add(it.code());
        Map<String, String> sectorByCode = resolveSectors(codes);

        String turnover = buildTurnoverRanking(sess, sess.label, time);  // 거래대금 섹터 랭킹(null 가능)
        String indexLine = domesticMarket.indexSummaryLine();            // 국내 지수 코스피·코스닥(null 가능)
        String fxLine = domesticMarket.fxSummaryLine();                  // 원달러 환율(null 가능)
        String usFuturesLine = usFutures.summaryLine();                  // 대외 여건(null 가능)
        // 시장 전체 수급 — 틱이 저장한 최신 스냅샷을 쓰되, 없으면(헤드라인 비활성 등) 즉석 조회.
        List<MarketInvestorFlow> mf = lastMarketFlows.isEmpty() ? fetchMarketFlows() : lastMarketFlows;
        String marketFlowLine = marketFlowLine(mf);                      // 시장 전체 외국인·기관(null 가능)
        String facts = buildFlowFacts(indexLine, marketFlowLine, fxLine, usFuturesLine, fb, fs, ib, is, db, turnover, sectorByCode);

        // 그라운딩 ON이면 실시간 검색으로 "왜?"를 끌어온다. 단 무료 티어/권한 문제로 그라운딩이 실패할 수 있어
        // 실패하면 평문(chat)으로 폴백한다 — 분석이 통째로 빠지지 않게(평문은 국내 실측만, "왜?"는 약함).
        String narrative = null;
        if (reportGrounding) {
            narrative = llm.chatGrounded(MACRO_SYSTEM_PROMPT, MACRO_USER_PROMPT + "\n\n" + facts);
            if (narrative == null) log.warn("그라운딩 실패 — 평문 분석으로 폴백(무료 티어/권한·한도 확인)");
        }
        if (narrative == null) {
            narrative = llm.chat(MACRO_SYSTEM_PROMPT, MACRO_USER_PROMPT + "\n\n" + facts);
        }
        if (narrative == null) return;  // LLM 실패 — 이미 로깅됨, 분석만 거른다
        String msg = String.format("🌐 **시황 분석** | %s%n%n%s", SUMMARY_TIME_FMT.format(time), narrative);
        try {
            reportNotifier.send(msg);   // 뉴스 채널로
            log.info("🌐 시황 매크로 분석 발송 (그라운딩 {})", reportGrounding ? "ON" : "OFF");
        } catch (Exception e) {
            log.warn("시황 매크로 분석 전송 실패", e);
        }
    }

    /**
     * 수급 기반 LLM 분석에 넣을 '실측 데이터' 블록을 만든다(전송·호출 없음 — 순수 함수, 테스트용).
     * 미선물 한 줄(있으면) → 외국인/기관 순매수·순매도(종목명(업종) +금액, TOP) → 외국인+기관 양매수 →
     * 거래대금 섹터 랭킹(있으면) 순. 업종은 sectorByCode에서 붙이되 미상·미분류면 생략한다.
     */
    static String buildFlowFacts(String indexLine, String marketFlowLine, String fxLine, String usFuturesLine,
                                 List<InvestorFlowItem> frgnBuys, List<InvestorFlowItem> frgnSells,
                                 List<InvestorFlowItem> instBuys, List<InvestorFlowItem> instSells,
                                 List<InvestorPairItem> dualBuy, String turnoverRanking,
                                 Map<String, String> sectorByCode) {
        StringBuilder sb = new StringBuilder();
        // 인과 사슬 순서로 맨 앞에: 지수(결과) → 시장 전체 수급(누가 사고파나) → 환율·미선물(대외 원인) → 그 뒤 종목별 수급.
        boolean hasMarket = false;
        if (indexLine != null) { sb.append(indexLine).append("\n"); hasMarket = true; }
        if (marketFlowLine != null) { sb.append(marketFlowLine).append("\n"); hasMarket = true; }
        if (fxLine != null) { sb.append(fxLine).append("\n"); hasMarket = true; }
        if (usFuturesLine != null) { sb.append(usFuturesLine).append("\n"); hasMarket = true; }
        if (hasMarket) sb.append("\n");
        sb.append("[외국인] 순매수: ").append(flowLine(frgnBuys, sectorByCode));
        sb.append("\n         순매도: ").append(flowLine(frgnSells, sectorByCode));
        sb.append("\n[기관] 순매수: ").append(flowLine(instBuys, sectorByCode));
        sb.append("\n       순매도: ").append(flowLine(instSells, sectorByCode));
        if (!dualBuy.isEmpty()) {
            String dual = dualBuy.stream()
                    .map(it -> it.name() + sectorTag(it.code(), sectorByCode))
                    .collect(Collectors.joining(", "));
            sb.append("\n[외국인+기관 양매수] ").append(dual);
        }
        if (turnoverRanking != null) sb.append("\n\n").append(turnoverRanking);
        return sb.toString();
    }

    /** 수급 한 줄 — "종목명(업종) +금액, ..." 형태. 비면 "(없음)". */
    private static String flowLine(List<InvestorFlowItem> items, Map<String, String> sectorByCode) {
        if (items.isEmpty()) return "(없음)";
        return items.stream()
                .map(it -> it.name() + sectorTag(it.code(), sectorByCode) + " " + formatNetWon(it.netValueWon()))
                .collect(Collectors.joining(", "));
    }

    /** 종목 업종을 "(반도체)"처럼 괄호로. 미상·미분류면 빈 문자열(괄호 생략). */
    private static String sectorTag(String code, Map<String, String> sectorByCode) {
        String s = sectorByCode.get(code);
        return (s != null && !s.isBlank() && !UNCLASSIFIED.equals(s)) ? "(" + s + ")" : "";
    }

    private static final String MACRO_SYSTEM_PROMPT =
            "너는 한국 주식시장 시황 분석가다. 아래 '국내 실측 데이터'(국내 지수 코스피·코스닥 등락, "
            + "시장 전체 외국인·기관 순매수(가집계), 원달러 환율, "
            + "미국 선물 등락률, 외국인·기관 수급 상위·업종, 외국인+기관 양매수, 거래대금 섹터 랭킹)는 사실이다. "
            + "여기에 더해 Google 검색으로 지금 시점의 ① 미국 선물·해외 증시가 오르거나 내리는 '이유' "
            + "② 한국의 정책 발표나 외국인 매도(또는 매수)를 유발한 이벤트, 원달러 환율이 움직인 배경 "
            + "③ 오늘과 이번 주 주요 경제 일정(FOMC·CPI·고용지표·주요 기업 실적 등)을 확인하라. "
            + "그런 다음 다음 순서로 '지금 시장이 왜 이렇게 움직이는지'를 한국어로 설명한다: "
            + "(1) 코스피·코스닥 지수 현황을 짚되 둘이 엇갈리면(디버전스) 그 점을 먼저 설명한다 "
            + "(2) 외국인·기관이 각각 무엇을 사고/파는지 수급을 정리한다 "
            + "(3) 외국인 매도(또는 매수)의 '이유'를 원달러 환율·미국 선물·국내 정책 발표·해외 이벤트와 엮어 설명한다 "
            + "(4) 마지막에 '오늘/이번 주 체크포인트'를 2~3개 불릿으로 정리한다. "
            + "검색으로 확인되지 않은 추측·소문은 쓰지 마라. 과장·투자권유 금지. 본문은 5~8문장.";

    private static final String MACRO_USER_PROMPT =
            "아래는 지금 국내 지수·환율·미국 선물·수급 실측이다. 위 지침의 순서(지수 디버전스 → 수급 → "
            + "외국인 매매 이유(환율·미선물·정책·해외 이벤트) → 체크포인트)대로, 검색을 활용해 "
            + "'왜 지금 시장이 이렇게 움직이는지'를 분석해줘.";

    /**
     * 급등 종목들의 업종 분포를 종목 수 비율 내림차순으로 정렬해 요약 메시지로 만든다.
     * 업종별로 비율(해당 업종 종목 수 / 전체 급등 종목 수)과 함께 소속 종목·등락률(% 내림차순)을 나열한다.
     * 상위 10개 업종까지 표시. (순수 함수 — 테스트용)
     */
    static String composeSectorSummary(Collection<Gainer> gainers, String session, LocalTime time) {
        int total = gainers.size();
        Map<String, List<Gainer>> bySector = new HashMap<>();
        for (Gainer g : gainers) {
            bySector.computeIfAbsent(g.sector(), k -> new ArrayList<>()).add(g);
        }
        List<Map.Entry<String, List<Gainer>>> ranked = new ArrayList<>(bySector.entrySet());
        ranked.sort(Comparator
                .comparingInt((Map.Entry<String, List<Gainer>> e) -> e.getValue().size()).reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **섹터 요약** | %s %s%n급등 %d종목 기준",
                session, SUMMARY_TIME_FMT.format(time), total));
        int rank = 1;
        for (Map.Entry<String, List<Gainer>> e : ranked) {
            List<Gainer> list = e.getValue();
            list.sort(Comparator.comparingDouble(Gainer::changePct).reversed());
            int pct = (int) Math.round(list.size() * 100.0 / total);
            String stocks = list.stream()
                    .map(g -> String.format("%s %+.1f%%", g.name(), g.changePct()))
                    .collect(Collectors.joining(", "));
            sb.append(String.format("%n%d. %s  %d%% (%d종목)%n   %s",
                    rank++, e.getKey(), pct, list.size(), stocks));
            if (rank > 10) break;
        }
        return sb.toString();
    }

    /** 섹터 요약 표시 단위 — 종목명·KRX 업종·등락률(%). 업종은 요약 시점에 조회해 채운다. */
    record Gainer(String name, String sector, double changePct) {}

    /**
     * 거래대금 상위 종목들을 업종별 거래대금 합계 내림차순으로 정렬해 섹터 랭킹 메시지로 만든다.
     * 상위 {@value #TURNOVER_TOP_SECTORS}개 섹터까지, 각 섹터 안 대표 종목 {@value #TURNOVER_STOCKS_PER_SECTOR}개를
     * 거래대금 내림차순으로 나열한다. 비중(%)은 표본 전체 거래대금 대비 해당 섹터 비율. (순수 함수 — 테스트용)
     */
    static String composeTurnoverRanking(Collection<Turnover> items, String session, LocalTime time) {
        long total = items.stream().mapToLong(Turnover::valueWon).sum();
        Map<String, List<Turnover>> bySector = new HashMap<>();
        for (Turnover t : items) {
            bySector.computeIfAbsent(t.sector(), k -> new ArrayList<>()).add(t);
        }
        // 섹터별 거래대금 합계로 내림차순, 동률은 업종명 사전순.
        List<Map.Entry<String, List<Turnover>>> ranked = new ArrayList<>(bySector.entrySet());
        ranked.sort(Comparator
                .comparingLong((Map.Entry<String, List<Turnover>> e) ->
                        e.getValue().stream().mapToLong(Turnover::valueWon).sum()).reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("💰 **거래대금 섹터 랭킹** | %s %s%n(거래대금 상위 %d종목 기준)",
                session, SUMMARY_TIME_FMT.format(time), items.size()));
        int rank = 1;
        for (Map.Entry<String, List<Turnover>> e : ranked) {
            List<Turnover> list = e.getValue();
            list.sort(Comparator.comparingLong(Turnover::valueWon).reversed());
            long sectorSum = list.stream().mapToLong(Turnover::valueWon).sum();
            int pct = total > 0 ? (int) Math.round(sectorSum * 100.0 / total) : 0;
            String stocks = list.stream()
                    .limit(TURNOVER_STOCKS_PER_SECTOR)
                    .map(t -> String.format("%s %s", t.name(), formatWon(t.valueWon())))
                    .collect(Collectors.joining(", "));
            sb.append(String.format("%n%d. %s  %s (%d%%)%n   %s",
                    rank++, e.getKey(), formatWon(sectorSum), pct, stocks));
            if (rank > TURNOVER_TOP_SECTORS) break;
        }
        return sb.toString();
    }

    /** 거래대금(원)을 "4.2조 / 380억 / 5,000만"처럼 사람이 읽기 쉬운 단위로 표기. */
    static String formatWon(long won) {
        double eok = won / 100_000_000.0;          // 1억 = 1e8
        if (eok >= 10_000) return String.format("%.1f조", eok / 10_000.0);
        if (eok >= 1) return String.format("%,.0f억", eok);
        return String.format("%,d만", won / 10_000L);
    }

    /** 거래대금 랭킹 표시 단위 — 종목명·KRX 업종·당일 거래대금(원). */
    record Turnover(String name, String sector, long valueWon) {}

    /**
     * 주기 틱(기본 10분) — 운영시간(정규장 09:00~15:40 + NXT 애프터마켓 15:40~20:00) 내내
     * 외국인·기관 수급 랭킹을 각각 1건씩 발송한다.
     * 단, 이 가집계 엔드포인트는 고정 시장코드(V)·정규장 증권사 입력치(마지막 14:30)만 반영하므로
     * NXT 애프터마켓엔 값이 갱신되지 않고 그날 최종 스냅샷이 반복된다(세션 라벨로 애프터마켓임을 표시).
     */
    /** 수급 발송 단계 — 시각에 따라 가집계 추정 폴링 / KRX 확정 1회 / NXT 최종 확정 1회로 갈린다. */
    enum FlowPhase { ESTIMATE, KRX_CONFIRMED, NXT_CONFIRMED }

    /**
     * 시각이 속한 수급 단계. 09:00~15:35 추정, 15:35~20:05 KRX 확정, 20:05~ NXT 최종 확정.
     * 09:00 이전이면 null(발송 없음). (순수 함수 — 테스트용)
     */
    static FlowPhase flowPhaseAt(LocalTime t) {
        if (!t.isBefore(NXT_CONFIRMED_AFTER)) return FlowPhase.NXT_CONFIRMED;   // ≥ 20:05
        if (!t.isBefore(KRX_CONFIRMED_AFTER)) return FlowPhase.KRX_CONFIRMED;   // ≥ 15:35
        if (!t.isBefore(REGULAR_OPEN)) return FlowPhase.ESTIMATE;               // ≥ 09:00
        return null;
    }

    /**
     * 확정 수급 조회용 시장구분 — 현재 시각으로 판단한다. NXT 최종 집계 시각(20:05) 이후면 NXT("NX"),
     * 그 전(정규장 확정 구간)이면 KRX("J"). 호출부가 넘기는 세션이 아니라 실제 시계로 결정한다. (순수 함수 — 테스트용)
     */
    static String confirmedMarketDiv(LocalTime t) {
        return !t.isBefore(NXT_CONFIRMED_AFTER) ? "NX" : "J";
    }

    private void investorFlowTick() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return;   // 주말 제외
        LocalTime time = now.toLocalTime();
        LocalDate today = now.toLocalDate();
        FlowPhase phase = flowPhaseAt(time);
        if (phase == null) return;   // 장 시작 전

        switch (phase) {
            case NXT_CONFIRMED -> {
                // NXT 최종 확정(20:05~) — 외국인·기관 확정 1회(NX). 당일 1회.
                if (today.equals(nxtConfirmedSentDate)) return;
                if (sendConfirmedInvestorFlow(Session.NXT_AFTER, time)) nxtConfirmedSentDate = today;
            }
            case KRX_CONFIRMED -> {
                // (a) 정규장 확정(15:35~) — 당일 1회. 확정이 아직 안 잡히면 false → 다음 틱 재시도.
                if (!today.equals(krxConfirmedSentDate) && sendConfirmedInvestorFlow(Session.REGULAR, time)) {
                    krxConfirmedSentDate = today;
                }
                // (b) NXT 외국인 수급 — 외국계 실시간(NX)으로 매 틱 폴링. NX 미지원/장외면 빈 응답 → 자동 스킵.
                //     (NXT는 기관 실시간 소스가 없어 외국인만 라이브로 본다.)
                sendForeignEstimateTable("NX", Session.NXT_AFTER.label, time);
            }
            case ESTIMATE -> sendEstimateFlow(time);   // 정규장 추정 10분 폴링(표 + 분석)
        }
    }

    /**
     * 시장 전체(코스피·코스닥) 외국인·기관 순매수 헤드라인을 주기로 KIS 채널에 보낸다(장중·평일만).
     * 종목별 랭킹과 별개로 "시장 전체로 누가 사고/파는지"를 한눈에. 최신 스냅샷은 시황 리포트도 읽는다.
     */
    private void marketFlowTick() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (currentSession(now) == null) return;   // 장외·주말이면 스킵
        LocalTime time = now.toLocalTime();
        List<MarketInvestorFlow> flows = fetchMarketFlows();
        if (flows.isEmpty()) return;   // 조회 실패(거부·빈응답) — 채널엔 안 보냄(로그만)
        lastMarketFlows = flows;       // 시황 리포트가 읽도록 저장
        try {
            notifier.send(composeMarketFlow(flows, time, "가집계"));   // 급등·수급표와 같은 KIS 채널
        } catch (Exception e) {
            log.warn("시장 전체 수급 헤드라인 전송 실패", e);
        }
    }

    /** 코스피·코스닥 시장 전체 수급을 각각 조회해 성공분만 모은다(가집계 시계열의 최신 누적). */
    private List<MarketInvestorFlow> fetchMarketFlows() {
        List<MarketInvestorFlow> flows = new ArrayList<>();
        MarketInvestorFlow kospi = client.marketInvestorFlow("코스피", MARKET_FLOW_KOSPI_ISCD, MARKET_FLOW_ISCD2);
        if (kospi != null) flows.add(kospi);
        MarketInvestorFlow kosdaq = client.marketInvestorFlow("코스닥", MARKET_FLOW_KOSDAQ_ISCD, MARKET_FLOW_ISCD2);
        if (kosdaq != null) flows.add(kosdaq);
        return flows;
    }

    /**
     * 정규장 추정 구간 — 외국인은 외국계 실시간(자주 갱신), 기관·동시매매는 거래소 가집계(4회/일)로 조회해
     * 표를 발송하고 같은 데이터로 LLM 분석 1건을 만든다.
     * 외국인을 외국계 실시간으로 바꿔 장중 수급 변화가 키움 실시간 외국인 수급처럼 자주 반영되게 한다.
     */
    private void sendEstimateFlow(LocalTime time) {
        Session sess = Session.REGULAR;   // 추정 구간은 항상 정규장 라벨(가집계는 시장코드 V 전용)
        // 외국인 — 외국계 실시간(정규장 J). 거래소 가집계 대신 사용해 더 자주 갱신.
        List<InvestorFlowItem> frgnBuys = client.foreignMemberEstimate("J", true);
        List<InvestorFlowItem> frgnSells = client.foreignMemberEstimate("J", false);
        // 기관 — 외국계 창구 구분이 안 돼 실시간 소스가 없으므로 거래소 가집계 유지.
        List<InvestorFlowItem> instBuys = client.investorFlowRank(KisClient.Investor.INSTITUTION, true);
        List<InvestorFlowItem> instSells = client.investorFlowRank(KisClient.Investor.INSTITUTION, false);
        List<InvestorPairItem> dualBuy = client.investorFlowDual(true).stream()
                .filter(it -> it.frgnWon() > 0 && it.orgnWon() > 0)   // 외국인·기관 둘 다 순매수
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon).reversed())
                .limit(INVESTOR_PAIR_TOP).toList();
        List<InvestorPairItem> dualSell = client.investorFlowDual(false).stream()
                .filter(it -> it.frgnWon() < 0 && it.orgnWon() < 0)   // 외국인·기관 둘 다 순매도
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon))
                .limit(INVESTOR_PAIR_TOP).toList();

        sendFlowTable(KisClient.Investor.FOREIGN, frgnBuys, frgnSells, sess.label, time, "외국계 실시간");
        sendFlowTable(KisClient.Investor.INSTITUTION, instBuys, instSells, sess.label, time, "가집계·추정");
        sendPairTable(dualBuy, dualSell, sess.label, time);
        // LLM 분석은 여기서 하지 않는다 — 시간당 generateMacroReport()가 뉴스 채널로 별도 발송.
    }

    /** 한 투자자의 순매수·순매도 상위를 각각 TOP{@value #INVESTOR_FLOW_TOP}로 잘라 표 1건 발송. 둘 다 비면 미발송. */
    private void sendFlowTable(KisClient.Investor inv, List<InvestorFlowItem> buys, List<InvestorFlowItem> sells,
                               String label, LocalTime time, String tag) {
        if (investorFlowMin <= 0) return;
        if (buys.isEmpty() && sells.isEmpty()) {
            log.info("{} 수급 랭킹 건너뜀 — 데이터 없음 ({} {})", tag, inv, label);
            return;
        }
        List<InvestorFlowItem> topBuys = buys.stream().limit(INVESTOR_FLOW_TOP).toList();
        List<InvestorFlowItem> topSells = sells.stream().limit(INVESTOR_FLOW_TOP).toList();
        log.info("{} 수급 랭킹 조립 ({} 매수 {}종목·매도 {}종목, {})",
                tag, inv, topBuys.size(), topSells.size(), label);
        try {
            notifier.send(composeInvestorFlow(inv, topBuys, topSells, label, time, tag));
        } catch (Exception e) {
            log.warn("수급 랭킹 알림 전송 실패 ({} {})", tag, inv, e);
        }
    }

    /**
     * 외국계 실시간(외국인) 수급 표 1건 발송 — 정규장은 marketDiv="J", NXT는 "NX".
     * 거래소 가집계(4회/일)와 달리 장중 더 자주 갱신되는 외국계 창구 추정(키움 실시간 외국인 수급 계열).
     * NX는 KIS 문서 미명시라 미지원/장외면 빈 응답 → 자동 스킵(되면 표 발송, 안 되면 발송 없음).
     */
    private void sendForeignEstimateTable(String marketDiv, String label, LocalTime time) {
        if (investorFlowMin <= 0) return;
        List<InvestorFlowItem> buys = client.foreignMemberEstimate(marketDiv, true);
        List<InvestorFlowItem> sells = client.foreignMemberEstimate(marketDiv, false);
        sendFlowTable(KisClient.Investor.FOREIGN, buys, sells, label, time, "외국계 실시간");
    }

    /** 양매수·양매도(이미 필터·정렬된) 종목을 동시매매 표 1건으로 발송. 둘 다 비면 미발송. */
    private void sendPairTable(List<InvestorPairItem> dualBuy, List<InvestorPairItem> dualSell,
                               String label, LocalTime time) {
        if (investorFlowMin <= 0) return;
        if (dualBuy.isEmpty() && dualSell.isEmpty()) {
            log.info("동시매매 랭킹 건너뜀 — 해당 종목 없음 ({})", label);
            return;
        }
        log.info("동시매매 랭킹 조립 (양매수 {}종목·양매도 {}종목, {})", dualBuy.size(), dualSell.size(), label);
        try {
            notifier.send(composeInvestorPair(dualBuy, dualSell, label, time, "가집계·추정"));
        } catch (Exception e) {
            log.warn("동시매매 랭킹 알림 전송 실패", e);
        }
    }

    /**
     * 마감 후 '확정' 수급 — 가집계 TOP 후보 종목을 inquire-investor 확정치로 재조회·재정렬해 외국인·기관·동시매매를 발송한다.
     * inquire-investor는 1회 호출로 외국인·기관 확정값을 함께 주므로, 후보 종목 합집합을 1회씩만 조회해 3개 메시지를 모두 만든다.
     * (전체 시장 확정 랭킹 엔드포인트는 KIS에 없어, 가집계가 띄운 종목으로 후보를 한정한다 — 가집계 미포착 종목은 누락될 수 있음.)
     *
     * @return 1건이라도 발송했으면 true(그 날 재발송 방지용).
     */
    private boolean sendConfirmedInvestorFlow(Session sess, LocalTime time) {
        if (investorFlowMin <= 0) return false;
        String label = sess.label;

        // 1) 가집계로 후보 종목(코드→종목명) 수집 — 외국인/기관 순매수·순매도 + 동시매매(ETC=0).
        Map<String, String> names = new HashMap<>();
        for (List<InvestorFlowItem> lst : List.of(
                client.investorFlowRank(KisClient.Investor.FOREIGN, true),
                client.investorFlowRank(KisClient.Investor.FOREIGN, false),
                client.investorFlowRank(KisClient.Investor.INSTITUTION, true),
                client.investorFlowRank(KisClient.Investor.INSTITUTION, false))) {
            for (InvestorFlowItem it : lst) names.putIfAbsent(it.code(), it.name());
        }
        for (List<InvestorPairItem> lst : List.of(client.investorFlowDual(true), client.investorFlowDual(false))) {
            for (InvestorPairItem it : lst) names.putIfAbsent(it.code(), it.name());
        }
        if (names.isEmpty()) {
            log.info("확정 수급 건너뜀 — 가집계 후보 종목 없음 ({})", label);
            return false;
        }

        // 2) 후보 종목 합집합을 inquire-investor로 1회씩 확정 조회(외국인·기관 동시 반환).
        //    시장구분은 현재 시각으로 판단한다 — 20:05 이후=NXT 기반(NX), 그 전=KRX 확정(J).
        String marketDiv = confirmedMarketDiv(time);
        Map<String, InvestorConfirmed> confirmed = new HashMap<>();
        for (String code : names.keySet()) {
            InvestorConfirmed c = client.inquireInvestorConfirmed(code, marketDiv);
            if (c != null) confirmed.put(code, c);
        }
        if (confirmed.isEmpty()) {
            log.warn("확정 수급 조회 전부 실패 — 발송 보류 ({})", label);
            return false;
        }
        String tag = confirmedDateTag(confirmed);   // "확정 06/25"
        log.info("확정 수급 조립 ({}개 후보 중 {}종목 확정, {})", names.size(), confirmed.size(), label);

        // 3) 외국인·기관 각각 확정값으로 재정렬해 발송, 동시매매도 확정값으로 발송.
        boolean sent = false;
        sent |= sendConfirmedFlow(KisClient.Investor.FOREIGN, names, confirmed, label, time, tag);
        sent |= sendConfirmedFlow(KisClient.Investor.INSTITUTION, names, confirmed, label, time, tag);
        sent |= sendConfirmedPair(names, confirmed, label, time, tag);
        return sent;
    }

    /** 한 투자자의 확정 순매수/순매도 상위를 재정렬해 1건 발송한다. 둘 다 비면 미발송(false). */
    private boolean sendConfirmedFlow(KisClient.Investor inv, Map<String, String> names,
                                      Map<String, InvestorConfirmed> confirmed, String label, LocalTime time, String tag) {
        List<InvestorFlowItem> items = confirmed.entrySet().stream()
                .map(e -> new InvestorFlowItem(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().netWon(inv), 0.0))
                .toList();
        List<InvestorFlowItem> buys = items.stream().filter(it -> it.netValueWon() > 0)
                .sorted(Comparator.comparingLong(InvestorFlowItem::netValueWon).reversed())
                .limit(INVESTOR_FLOW_TOP).toList();
        List<InvestorFlowItem> sells = items.stream().filter(it -> it.netValueWon() < 0)
                .sorted(Comparator.comparingLong(InvestorFlowItem::netValueWon))
                .limit(INVESTOR_FLOW_TOP).toList();
        if (buys.isEmpty() && sells.isEmpty()) return false;
        try {
            notifier.send(composeInvestorFlow(inv, buys, sells, label, time, tag));
            return true;
        } catch (Exception e) {
            log.warn("확정 수급 랭킹 알림 전송 실패 ({})", inv, e);
            return false;
        }
    }

    /** 외국인·기관 둘 다 순매수(양매수)·둘 다 순매도(양매도)인 종목을 확정값으로 추려 1건 발송한다. 둘 다 비면 미발송. */
    private boolean sendConfirmedPair(Map<String, String> names, Map<String, InvestorConfirmed> confirmed,
                                      String label, LocalTime time, String tag) {
        List<InvestorPairItem> items = confirmed.entrySet().stream()
                .map(e -> new InvestorPairItem(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().foreignWon(), e.getValue().institutionWon(), 0.0))
                .toList();
        List<InvestorPairItem> dualBuy = items.stream()
                .filter(it -> it.frgnWon() > 0 && it.orgnWon() > 0)
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon).reversed())
                .limit(INVESTOR_PAIR_TOP).toList();
        List<InvestorPairItem> dualSell = items.stream()
                .filter(it -> it.frgnWon() < 0 && it.orgnWon() < 0)
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon))
                .limit(INVESTOR_PAIR_TOP).toList();
        if (dualBuy.isEmpty() && dualSell.isEmpty()) return false;
        try {
            notifier.send(composeInvestorPair(dualBuy, dualSell, label, time, tag));
            return true;
        } catch (Exception e) {
            log.warn("확정 동시매매 알림 전송 실패", e);
            return false;
        }
    }

    /** 확정 조회 결과의 거래일자(YYYYMMDD)로 "확정 MM/DD" 태그를 만든다. 날짜 미상이면 "확정". */
    private static String confirmedDateTag(Map<String, InvestorConfirmed> confirmed) {
        for (InvestorConfirmed c : confirmed.values()) {
            String d = c.date();
            if (d != null && d.length() == 8) {
                return "확정 " + d.substring(4, 6) + "/" + d.substring(6, 8);
            }
        }
        return "확정";
    }

    /** 수급 표 종목명 칸 표시폭(한글=2). "SK하이닉스"·"LG에너지솔루션" 등 대부분 수용. */
    private static final int FLOW_NAME_W = 12;
    /** 수급 표 순매수금액 칸 표시폭(우측정렬). "+1,234억"·"-987억" 등 수용. */
    private static final int FLOW_AMT_W = 9;
    /** 동시매매 표 외국인/기관 금액 칸 표시폭(우측정렬). */
    private static final int PAIR_AMT_W = 10;

    /**
     * 한 투자자의 순매수 상위(가장 많이 산)·순매도 상위(가장 많이 판)를 좌우 2열로 나란히 둔 표를 만든다.
     * Webex 마크다운은 표(| |)를 렌더하지 않으므로 코드블록(고정폭) 안에 한글 표시폭을 맞춰 ASCII로 정렬한다.
     * 셀은 종목명 + 순매수금액(컴팩트, 등락률 생략). 같은 순위 행에 매수·매도가 함께 온다. (순수 함수 — 테스트용)
     */
    /**
     * 시장 전체(코스피·코스닥) 외국인·기관 순매수 헤드라인 메시지. (순수 함수 — 테스트용)
     * 예: "📊 **시장 수급** | 13:40 (가집계)\n코스피  🌍 외국인 -3,200억 · 🏛 기관 +1,500억\n코스닥 ...".
     */
    static String composeMarketFlow(List<MarketInvestorFlow> flows, LocalTime time, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **시장 수급** | %s  (%s)", SUMMARY_TIME_FMT.format(time), tag));
        for (MarketInvestorFlow f : flows) {
            sb.append(String.format("%n%s  %s %s · %s %s",
                    f.market(),
                    KisClient.Investor.FOREIGN.label(), formatNetWon(f.foreignNetWon()),
                    KisClient.Investor.INSTITUTION.label(), formatNetWon(f.institutionNetWon())));
        }
        return sb.toString();
    }

    /**
     * 시황 리포트 facts용 시장 전체 수급 한 줄(컴팩트). 비면 null.
     * 예: "📊 시장 수급 | 코스피 외국인 -3,200억·기관 +1,500억 / 코스닥 외국인 +420억·기관 -180억". (순수 함수 — 테스트용)
     */
    static String marketFlowLine(List<MarketInvestorFlow> flows) {
        if (flows.isEmpty()) return null;
        String body = flows.stream()
                .map(f -> String.format("%s 외국인 %s·기관 %s",
                        f.market(), formatNetWon(f.foreignNetWon()), formatNetWon(f.institutionNetWon())))
                .collect(Collectors.joining(" / "));
        return "📊 시장 수급 | " + body;
    }

    static String composeInvestorFlow(KisClient.Investor inv, List<InvestorFlowItem> buys,
                                      List<InvestorFlowItem> sells, String session, LocalTime time, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s **수급 TOP%d** | %s %s  (%s)",
                inv.label(), INVESTOR_FLOW_TOP, session, SUMMARY_TIME_FMT.format(time), tag));

        int rows = Math.max(buys.size(), sells.size());
        if (rows == 0) {
            sb.append(String.format("%n```%n(데이터 없음)%n```"));
            return sb.toString();
        }

        int cellW = FLOW_NAME_W + 1 + FLOW_AMT_W;   // 종목명 + 공백 + 금액
        sb.append(String.format("%n```"));
        // 헤더 + 구분선
        sb.append(String.format("%n %s %s %s",
                padDisplay("#", 2, false),
                padDisplay("매수(많이산)", cellW, true),
                padDisplay("매도(많이판)", cellW, true)));
        sb.append(String.format("%n %s %s %s",
                "--", "-".repeat(cellW), "-".repeat(cellW)));
        for (int i = 0; i < rows; i++) {
            String buyCell = i < buys.size() ? flowCell(buys.get(i)) : " ".repeat(cellW);
            String sellCell = i < sells.size() ? flowCell(sells.get(i)) : "";
            sb.append(String.format("%n %s %s %s",
                    padDisplay(Integer.toString(i + 1), 2, false), buyCell, sellCell));
        }
        sb.append(String.format("%n```"));
        return sb.toString();
    }

    /** 수급 표 한 칸 — "종목명(좌측정렬)  순매수금액(우측정렬)". */
    private static String flowCell(InvestorFlowItem it) {
        return padDisplay(it.name(), FLOW_NAME_W, true)
                + " " + padDisplay(formatNetWon(it.netValueWon()), FLOW_AMT_W, false);
    }

    /**
     * 양매수·양매도 종목을 종목 / 외국인 / 기관 3열 표(코드블록)로 정리한 메시지를 만든다. (순수 함수 — 테스트용)
     */
    static String composeInvestorPair(List<InvestorPairItem> dualBuy, List<InvestorPairItem> dualSell,
                                      String session, LocalTime time, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🤝 **외국인+기관 동시매매** | %s %s  (%s)",
                session, SUMMARY_TIME_FMT.format(time), tag));
        sb.append(String.format("%n```"));
        appendPairSection(sb, "[양매수 TOP" + INVESTOR_PAIR_TOP + "] 외국인·기관 둘 다 순매수", dualBuy);
        appendPairSection(sb, "[양매도 TOP" + INVESTOR_PAIR_TOP + "] 외국인·기관 둘 다 순매도", dualSell);
        sb.append(String.format("%n```"));
        return sb.toString();
    }

    /** 동시매매 한 섹션 — 헤더 + "순번 종목 외국인 기관" 표. 비면 안내 문구. */
    private static void appendPairSection(StringBuilder sb, String header, List<InvestorPairItem> items) {
        sb.append(String.format("%n%s", header));
        sb.append(String.format("%n %s %s %s %s",
                padDisplay("#", 2, false),
                padDisplay("종목", FLOW_NAME_W, true),
                padDisplay("외국인", PAIR_AMT_W, false),
                padDisplay("기관", PAIR_AMT_W, false)));
        if (items.isEmpty()) {
            sb.append(String.format("%n (해당 종목 없음)"));
            return;
        }
        int rank = 1;
        for (InvestorPairItem it : items) {
            sb.append(String.format("%n %s %s %s %s",
                    padDisplay(Integer.toString(rank++), 2, false),
                    padDisplay(it.name(), FLOW_NAME_W, true),
                    padDisplay(formatNetWon(it.frgnWon()), PAIR_AMT_W, false),
                    padDisplay(formatNetWon(it.orgnWon()), PAIR_AMT_W, false)));
        }
    }

    /**
     * 표시폭(한글 등 전각=2, 그 외=1) 기준으로 문자열을 자르고 공백 패딩한다 — 코드블록 고정폭 정렬용.
     * @param left true면 좌측정렬(뒤에 공백), false면 우측정렬(앞에 공백). (순수 함수 — 테스트용)
     */
    static String padDisplay(String s, int width, boolean left) {
        if (s == null) s = "";
        // 폭을 넘으면 표시폭 기준으로 자른다(마지막 전각 문자가 폭을 넘기지 않도록).
        int w = 0;
        StringBuilder cut = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = isWideChar(c) ? 2 : 1;
            if (w + cw > width) break;
            cut.append(c);
            w += cw;
        }
        String body = cut.toString();
        String pad = " ".repeat(Math.max(0, width - w));
        return left ? body + pad : pad + body;
    }

    /** 전각(표시폭 2) 문자인지 — 한글 음절·자모, CJK, 전각기호 등. (이모지는 폭이 들쭉날쭉해 표 본문엔 쓰지 않는다) */
    private static boolean isWideChar(char c) {
        return (c >= 0x1100 && c <= 0x115F)    // 한글 자모
                || (c >= 0x2E80 && c <= 0xA4CF) // CJK 부수~한자
                || (c >= 0xAC00 && c <= 0xD7A3) // 한글 음절
                || (c >= 0xF900 && c <= 0xFAFF) // CJK 호환 한자
                || (c >= 0xFF00 && c <= 0xFF60) // 전각 영숫자·기호
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    /** 순매수 거래대금(원)을 부호와 함께 표기 — 음수(순매도)면 '-' 접두, 0이면 0. {@link #formatWon} 재사용. */
    static String formatNetWon(long won) {
        if (won == 0) return "0";
        String sign = won < 0 ? "-" : "+";
        return sign + formatWon(Math.abs(won));
    }

    /** 전일 대비 등락률이 임계 이상인 "오늘 많이 오른" 종목인지. (순수 함수 — 테스트용) */
    static boolean isBigGainer(VolumeRankItem it, double minChangePct) {
        return it.changePct() >= minChangePct;
    }

    private boolean cooledDown(String code, Instant now) {
        Instant last = lastAlert.get(code);
        return last == null || Duration.between(last, now).compareTo(cooldown) >= 0;
    }

    /** 현재 시각의 시장 세션 — 주말·운영시간 밖이면 null(폴링·요약 안 함). */
    private Session currentSession(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return null;
        return sessionAt(now.toLocalTime());
    }
}
