package com.example.dart.kis;

import com.example.dart.config.AppConfig;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
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

    private final KisClient client;
    private final Notifier notifier;
    private final KisAlertComposer alertComposer;
    private final int intervalSec;
    private final double minChangePct;
    private final Duration cooldown;
    /** 섹터 요약 주기(분). 0이면 섹터 요약 비활성. */
    private final int sectorSummaryMin;

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

    public KisPollerService(KisClient client, Notifier notifier,
                            KisAlertComposer alertComposer, AppConfig config, Path sectorsFile) {
        this.client = client;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.intervalSec = config.kisPollIntervalSec();
        this.minChangePct = config.kisMinChangePct();
        this.cooldown = Duration.ofMinutes(config.kisCooldownMin());
        this.sectorSummaryMin = config.kisSectorSummaryMin();
        this.sectorsFile = sectorsFile;
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
        List<VolumeRankItem> snapshot;
        try {
            snapshot = new ArrayList<>(client.topGainers(session.marketDiv));
        } catch (Exception e) {
            log.warn("섹터 요약 라이브 조회 실패 ({}): {}", label, e.toString());
            return;
        }
        snapshot.removeIf(it -> !isBigGainer(it, minChangePct));  // 현재 ≥임계인 종목만
        if (snapshot.isEmpty()) {
            log.info("섹터 요약 건너뜀 — 급등 종목 없음 ({})", label);
            return;
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
        String msg = composeSectorSummary(resolved, label, time);
        log.info("섹터 요약 발송 ({}종목, 업종분류 {}/{}, {})",
                resolved.size(), classified, resolved.size(), label);
        if (classified == 0) {
            log.warn("섹터 업종 조회가 전부 실패 — KIS 업종(inquire-price) 응답/권한·유량제한 확인 필요");
        }
        try {
            notifier.send(msg);
        } catch (Exception e) {
            log.warn("섹터 요약 알림 전송 실패", e);
        }
    }

    /**
     * 거래대금 상위 종목을 라이브 조회해 업종별 거래대금을 합산, "지금 어느 섹터가 활발한가"를
     * 거래대금 내림차순 섹터 랭킹 1건으로 발송한다(급등 섹터 요약과 별도 메시지).
     * 급등 종목이 없어도 거래대금은 장중 항상 존재하므로 독립적으로 동작한다.
     */
    private void sendTurnoverRanking(Session session, String label, LocalTime time) {
        if (sectorSummaryMin <= 0) return;
        List<TradingValueItem> snapshot;
        try {
            snapshot = client.topByTradingValue(session.marketDiv);
        } catch (Exception e) {
            log.warn("거래대금 랭킹 라이브 조회 실패 ({}): {}", label, e.toString());
            return;
        }
        if (snapshot.isEmpty()) {
            log.info("거래대금 랭킹 건너뜀 — 종목 없음 ({})", label);
            return;
        }
        Map<String, String> sectors = resolveSectors(
                snapshot.stream().map(TradingValueItem::code).toList());
        List<Turnover> resolved = new ArrayList<>(snapshot.size());
        for (TradingValueItem it : snapshot) {
            resolved.add(new Turnover(it.name(),
                    sectors.getOrDefault(it.code(), UNCLASSIFIED), it.tradingValueWon()));
        }
        String msg = composeTurnoverRanking(resolved, label, time);
        log.info("거래대금 랭킹 발송 ({}종목, {})", resolved.size(), label);
        try {
            notifier.send(msg);
        } catch (Exception e) {
            log.warn("거래대금 랭킹 알림 전송 실패", e);
        }
    }

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
