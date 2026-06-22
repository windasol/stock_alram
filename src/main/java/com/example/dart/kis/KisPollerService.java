package com.example.dart.kis;

import com.example.dart.config.AppConfig;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * 폴링 운영시간 — 시장구분에 따라 달라진다.
     *  - KRX(J): 정규장 09:00~15:40(마감 동시호가 여유). NXT 데이터가 없으므로 연장세션을 봐도 의미 없음.
     *  - NXT(UN/NX): 08:00~20:00 (프리마켓 08:00~09:00 · 정규장 09:00~15:30 · 애프터마켓 15:30~20:00).
     * 이 시간 밖(야간·주말)엔 폴링하지 않는다.
     */
    private static final LocalTime KRX_OPEN = LocalTime.of(9, 0);
    private static final LocalTime KRX_CLOSE = LocalTime.of(15, 40);
    private static final LocalTime NXT_OPEN = LocalTime.of(8, 0);
    private static final LocalTime NXT_CLOSE = LocalTime.of(20, 0);
    /** 세션 경계 — NXT 모드에서 알림에 "어느 장 시간대"인지 표시해 정규장 vs NXT 연장세션을 구분한다. */
    private static final LocalTime MAIN_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MAIN_CLOSE = LocalTime.of(15, 30);

    private static final DateTimeFormatter SUMMARY_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    /** 업종 미상 종목의 섹터 라벨. */
    private static final String UNCLASSIFIED = "미분류";

    private final KisClient client;
    private final Notifier notifier;
    private final KisAlertComposer alertComposer;
    private final int intervalSec;
    private final double minChangePct;
    private final Duration cooldown;
    /** UN/NX 시장구분이면 NXT 모드 — 운영시간을 08:00~20:00으로 확장하고 세션(프리/정규/애프터)을 라벨링한다. */
    private final boolean nxtMode;
    private final LocalTime open;
    private final LocalTime close;
    /** 섹터 요약 주기(분). 0이면 섹터 요약 비활성. */
    private final int sectorSummaryMin;

    /** 종목코드 → 마지막 알림 시각. 쿨다운 내 재알림 억제. */
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();
    /** 오늘 급등한 종목코드 → KRX 업종명. 섹터 요약 집계용(쿨다운과 무관하게 누적). 자정에 리셋. */
    private final Map<String, String> sectorByCode = new ConcurrentHashMap<>();
    /** 누적 기준 날짜 — 날이 바뀌면 sectorByCode를 비운다. */
    private volatile LocalDate currentDay;
    private final ScheduledExecutorService scheduler;

    private int consecutiveFailures = 0;
    private int skipPolls = 0;

    public KisPollerService(KisClient client, Notifier notifier,
                            KisAlertComposer alertComposer, AppConfig config) {
        this.client = client;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.intervalSec = config.kisPollIntervalSec();
        this.minChangePct = config.kisMinChangePct();
        this.cooldown = Duration.ofMinutes(config.kisCooldownMin());
        String mkt = config.kisMarketDivCode();
        this.nxtMode = "UN".equals(mkt) || "NX".equals(mkt);
        this.open = nxtMode ? NXT_OPEN : KRX_OPEN;
        this.close = nxtMode ? NXT_CLOSE : KRX_CLOSE;
        this.sectorSummaryMin = config.kisSectorSummaryMin();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "kis-poller"));
    }

    public void start() {
        log.info("KIS 급등 폴링 시작 (시장 {}{}, 주기 {}초, 운영 {}~{} KST, 임계 등락률≥{}%)",
                client.marketDivCode(), nxtMode ? "(NXT 연장세션 포함)" : "",
                intervalSec, open, close, minChangePct);
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSec, TimeUnit.SECONDS);

        if (sectorSummaryMin > 0) {
            long periodSec = sectorSummaryMin * 60L;
            // 벽시계 경계(예: 30분이면 매시 :00·:30)에 맞춰 첫 발송 시각을 정렬한다.
            long initialDelaySec = periodSec - (Instant.now().getEpochSecond() % periodSec);
            scheduler.scheduleWithFixedDelay(this::summarize, initialDelaySec, periodSec, TimeUnit.SECONDS);
            log.info("KIS 섹터 요약 활성 ({}분 주기, 첫 발송 {}초 후)", sectorSummaryMin, initialDelaySec);
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
        if (!isMarketOpen(now)) return;
        rolloverIfNewDay(now.toLocalDate());

        List<VolumeRankItem> items;
        try {
            items = client.topGainers();
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            skipPolls = Math.min(1 << consecutiveFailures, 40);
            log.warn("KIS 조회 실패 ({}연속) — {}회 폴링 건너뜀: {}",
                    consecutiveFailures, skipPolls, e.toString());
            return;
        }

        Instant nowInstant = now.toInstant();
        // 정규장(KRX) 모드에선 시간대 구분이 의미 없으므로 "정규장" 고정. NXT 모드에서만 프리/애프터마켓을 라벨링한다.
        String session = nxtMode ? sessionLabel(now.toLocalTime()) : "정규장";
        for (VolumeRankItem it : items) {
            if (!isBigGainer(it, minChangePct)) continue;
            recordSector(it);  // 섹터 요약 누적 — 쿨다운으로 알림이 억제돼도 집계엔 포함한다.
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

    /** 급등 종목의 업종을 한 번만 조회해 누적한다(같은 코드는 캐시). 업종 미상이면 "미분류". */
    private void recordSector(VolumeRankItem it) {
        if (sectorSummaryMin <= 0) return;
        if (sectorByCode.containsKey(it.code())) return;
        String sector = client.sectorOf(it.code());
        sectorByCode.put(it.code(), (sector == null || sector.isBlank()) ? UNCLASSIFIED : sector);
    }

    /** 날이 바뀌면 누적 섹터 집계를 비운다(매일 0시 기준 새 집계). */
    private void rolloverIfNewDay(LocalDate today) {
        if (!today.equals(currentDay)) {
            currentDay = today;
            sectorByCode.clear();
        }
    }

    /** 누적된 급등 종목들의 업종을 집계해 섹터 요약 1건을 발송한다. 누적이 없으면 건너뛴다. */
    private void summarize() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!isMarketOpen(now)) return;
        rolloverIfNewDay(now.toLocalDate());

        Map<String, String> snapshot = new HashMap<>(sectorByCode);
        if (snapshot.isEmpty()) {
            log.info("섹터 요약 건너뜀 — 아직 급등 종목 없음");
            return;
        }
        String session = nxtMode ? sessionLabel(now.toLocalTime()) : "정규장";
        String msg = composeSectorSummary(snapshot, session, now.toLocalTime());
        log.info("섹터 요약 발송 ({}종목)", snapshot.size());
        try {
            notifier.send(msg);
        } catch (Exception e) {
            log.warn("섹터 요약 알림 전송 실패", e);
        }
    }

    /**
     * 급등 종목들의 업종 분포를 종목 수 비율 내림차순으로 정렬해 요약 메시지로 만든다.
     * 비율 = 해당 업종 종목 수 / 전체 급등 종목 수. 상위 10개 업종까지 표시. (순수 함수 — 테스트용)
     */
    static String composeSectorSummary(Map<String, String> sectorByCode, String session, LocalTime time) {
        int total = sectorByCode.size();
        Map<String, Integer> counts = new HashMap<>();
        for (String sector : sectorByCode.values()) {
            counts.merge(sector, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **섹터 요약** | %s %s%n급등 %d종목 기준%n",
                session, SUMMARY_TIME_FMT.format(time), total));
        int rank = 1;
        for (Map.Entry<String, Integer> e : ranked) {
            int pct = (int) Math.round(e.getValue() * 100.0 / total);
            sb.append(String.format("%d. %s  %d%% (%d종목)%n", rank++, e.getKey(), pct, e.getValue()));
            if (rank > 10) break;
        }
        return sb.toString().stripTrailing();
    }

    /** 감지 시각이 속한 시장 세션 라벨 — 정규장 vs NXT 프리/애프터마켓 구분(알림 표시용). (순수 함수 — 테스트용) */
    static String sessionLabel(LocalTime t) {
        if (t.isBefore(MAIN_OPEN))  return "🌅 NXT 프리마켓";
        if (t.isBefore(MAIN_CLOSE)) return "정규장";
        return "🌆 NXT 애프터마켓";
    }

    /** 전일 대비 등락률이 임계 이상인 "오늘 많이 오른" 종목인지. (순수 함수 — 테스트용) */
    static boolean isBigGainer(VolumeRankItem it, double minChangePct) {
        return it.changePct() >= minChangePct;
    }

    private boolean cooledDown(String code, Instant now) {
        Instant last = lastAlert.get(code);
        return last == null || Duration.between(last, now).compareTo(cooldown) >= 0;
    }

    private boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(open) && !t.isAfter(close);
    }
}
