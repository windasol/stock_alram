package com.example.dart.service;

import com.example.dart.kis.KisClient;
import com.example.dart.kis.MinuteCandle;
import com.example.dart.model.Disclosure;
import com.example.dart.notify.Notifier;
import com.example.dart.quote.StockQuoteClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 호재 공시 감지 시각(t0)을 기준으로 "공시 후 10분간 어떻게 움직였나"를 통계로 남긴다.
 *
 * 흐름(재설계): 감지 즉시 폴링하지 않고, 공시 시각 +10분(+버퍼) 뒤에 딱 한 번 KIS 1분봉으로
 * [공시-2분 ~ 공시+10분] 창을 통째로 조회해 분석한다. 10초마다 현재가를 메모리에 적립하던 방식을
 * 없앤 것 — 메모리/네트워크 부담이 작고, 기준가를 "공시 2분 전"으로 잡아 공시 직전 급등(VI 등)에
 * 오염되지 않은 깨끗한 출발점에서 변동을 잰다.
 *
 * 기준가(시작가)는 공시 2분 전 봉의 종가다. 고점/저점/종료가는 공시 시점 이후 구간에서만 본다.
 *
 * 추적 시간대는 NXT 연장세션을 포함한 08:00~20:00(평일). 그 밖(마감 후·새벽·주말)에 뜬 공시는
 * 의미가 없어 건너뛴다. 공시+10분이 마감(20:00)을 넘으면 마감까지로 잘라 분석한다.
 *
 * 종목코드가 없는 공시(코넥스·비상장 등)나 KIS 미설정 시엔 추적하지 않는다(분봉 조회 불가).
 */
public class DisclosurePriceTracker {

    private static final Logger log = LoggerFactory.getLogger(DisclosurePriceTracker.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    /** 고점/저점 발생 시각 표기용 — "10:07". */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** 추적 운영시간 — NXT 프리마켓 08:00 ~ 애프터마켓 20:00. 이 창 밖이면 추적하지 않는다. */
    private static final LocalTime TRACK_OPEN = LocalTime.of(8, 0);
    private static final LocalTime TRACK_CLOSE = LocalTime.of(20, 0);
    /** KRX 정규장 — 이 밖(프리·애프터마켓)은 NXT만 거래하므로 분봉을 통합("UN")으로 받아야 한다. */
    private static final LocalTime REG_OPEN = LocalTime.of(9, 0);
    private static final LocalTime REG_CLOSE = LocalTime.of(15, 30);

    private static final int PRE_MIN = 2;       // 기준가를 잡는 공시 전 시점(분)
    private static final int WINDOW_MIN = 10;   // 공시 후 분석 창(분)
    /** 분석 예약 지연 — 공시+10분 봉이 완성되도록 30초 버퍼를 더한다. */
    private static final long ANALYZE_DELAY_SEC = WINDOW_MIN * 60L + 30;
    /** 횡보/유의미 변동을 가르는 임계(%). 이보다 작은 변동은 "안 움직였다"로 본다. */
    private static final double FLAT_EPS_PCT = 0.5;

    /** 시총 등 다른 조회 일관성을 위해 보관(현재 추적 분석엔 분봉만 쓴다). */
    private final StockQuoteClient quoteClient;
    private final KisClient kisClient;
    private final Notifier notifier;
    private final Path storeFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService pool =
            Executors.newScheduledThreadPool(2, r -> new Thread(r, "price-tracker"));

    public DisclosurePriceTracker(StockQuoteClient quoteClient, KisClient kisClient,
                                  Notifier notifier, Path storeFile) {
        this.quoteClient = quoteClient;
        this.kisClient = kisClient;
        this.notifier = notifier;
        this.storeFile = storeFile;
    }

    /**
     * 공시 1건 추적을 예약한다(비동기). 공시 시각 +10분 뒤에 분봉 1콜로 분석한다.
     * 종목코드 없음·KIS 미설정·추적 시간대 아님이면 조용히 건너뛴다.
     */
    public void track(Disclosure d, String category) {
        String code = d.stockCode();
        if (code == null || code.isBlank()) return;   // 코넥스·비상장 등 — 추적 불가
        if (kisClient == null) {
            log.info("주가 추적 생략(KIS 미설정 — 분봉 조회 불가): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        ZonedDateTime t0 = ZonedDateTime.now(KST);
        if (!withinWindow(t0)) {
            log.info("주가 추적 생략(장외): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        pool.schedule(() -> analyze(d, category, t0), ANALYZE_DELAY_SEC, TimeUnit.SECONDS);
        log.info("주가 추적 예약 [{}] {} - {} — {}분 뒤 [공시-{}분~공시+{}분] 분봉 분석",
                code, d.corpName(), d.reportNm(), WINDOW_MIN, PRE_MIN, WINDOW_MIN);
    }

    public void stop() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 공시+10분 뒤 한 번 실행 — 분봉을 조회해 통계를 내고 알림·저장한다. */
    private void analyze(Disclosure d, String category, ZonedDateTime t0) {
        try {
            LocalTime t0Min = t0.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
            LocalTime fromMin = t0Min.minusMinutes(PRE_MIN);
            if (fromMin.isBefore(TRACK_OPEN)) fromMin = TRACK_OPEN;
            LocalTime toMin = t0Min.plusMinutes(WINDOW_MIN);
            if (toMin.isAfter(TRACK_CLOSE)) toMin = TRACK_CLOSE;

            // 시장구분 자동 선택: 창[fromMin~toMin]이 정규장(09:00~15:30) 안에 다 들어오면 KRX("J"),
            // 한쪽이라도 정규장 밖(프리·애프터마켓, NXT만 거래)이면 통합("UN")으로 받는다(경계 공시도 커버).
            // UN이 비거나(미지원·무권한) 데이터가 없으면 J로 한 번 더 시도 — 정규장은 절대 안 깨지게.
            String endHms = toMin.format(HHMMSS);
            boolean extended = nxtSession(fromMin) || nxtSession(toMin);
            String marketDiv = extended ? "UN" : "J";
            List<MinuteCandle> candles = kisClient.minuteCandles(d.stockCode(), endHms, marketDiv);
            if (candles.isEmpty() && !"J".equals(marketDiv)) {
                candles = kisClient.minuteCandles(d.stockCode(), endHms, "J");
            }
            Stats st = computeStats(candles, t0Min, fromMin, toMin);
            if (st == null) {
                log.warn("주가 추적 생략(분봉 부족 — NXT 연장세션·데이터 없음): {} {} - {}",
                        d.stockCode(), d.corpName(), d.reportNm());
                return;
            }

            log.info("주가 추적 종료 [{}] {} — 기준가 {}원(공시 {}분 전) / 종료 {}%({}분) / 고점 {}%({}분) / 저점 {}%({}분) / {}",
                    d.stockCode(), d.corpName(), st.baseline(), st.baselineOffsetMin(),
                    round1(st.endPct()), st.endMin(), round1(st.mfePct()), st.peakMin(),
                    round1(st.maePct()), st.troughMin(), st.pattern());

            notifier.send(composeMessage(d, st, t0));
            persist(d, category, t0, st);
        } catch (Exception e) {
            log.warn("주가 추적 분석 오류: {} - {}", d.corpName(), d.reportNm(), e);
        }
    }

    /**
     * 분봉 목록에서 통계를 낸다(순수 함수 — 네트워크 분리, 테스트용 패키지 가시성).
     * 기준가 = 공시 PRE_MIN분 전 봉의 종가(없으면 창 내 가장 이른 봉). 고점/저점/종료가는
     * 공시 시점 이후 구간 [t0Min, toMin]에서만 본다(공시 전 봉은 기준가용일 뿐).
     *
     * @return 통계. 창에 봉이 없거나 공시 후 봉이 없으면 null(분석 생략).
     */
    static Stats computeStats(List<MinuteCandle> candles, LocalTime t0Min, LocalTime fromMin, LocalTime toMin) {
        TreeMap<LocalTime, MinuteCandle> byTime = new TreeMap<>();
        for (MinuteCandle c : candles) {
            if (!c.time().isBefore(fromMin) && !c.time().isAfter(toMin)) byTime.put(c.time(), c);
        }
        if (byTime.isEmpty()) return null;

        Map.Entry<LocalTime, MinuteCandle> baseEntry = byTime.ceilingEntry(fromMin);
        if (baseEntry == null) return null;
        long baseline = baseEntry.getValue().close();
        if (baseline <= 0) return null;
        long baselineOffsetMin = ChronoUnit.MINUTES.between(baseEntry.getKey(), t0Min);

        NavigableMap<LocalTime, MinuteCandle> post = byTime.subMap(t0Min, true, toMin, true);
        if (post.isEmpty()) return null;

        long endPrice = post.lastEntry().getValue().close();
        long endMin = ChronoUnit.MINUTES.between(t0Min, post.lastKey());

        long discPrice = post.firstEntry().getValue().close();   // 공시 시점(분) 가격 = 시작가
        double discPct = pct(discPrice, baseline);               // 공시 직전(기준가) 대비 = 이미 오른 폭

        long peakPrice = Long.MIN_VALUE, troughPrice = Long.MAX_VALUE;
        LocalTime peakTime = post.firstKey(), troughTime = post.firstKey();
        for (Map.Entry<LocalTime, MinuteCandle> e : post.entrySet()) {
            MinuteCandle c = e.getValue();
            long hi = c.high() > 0 ? c.high() : c.close();   // 고/저가 누락 봉은 종가로 대체
            long lo = c.low() > 0 ? c.low() : c.close();
            if (hi > peakPrice) { peakPrice = hi; peakTime = e.getKey(); }
            if (lo < troughPrice) { troughPrice = lo; troughTime = e.getKey(); }
        }

        long peakMin = ChronoUnit.MINUTES.between(t0Min, peakTime);
        long troughMin = ChronoUnit.MINUTES.between(t0Min, troughTime);
        double endPct = pct(endPrice, baseline);
        double mfePct = pct(peakPrice, baseline);
        double maePct = pct(troughPrice, baseline);
        String pattern = classify(mfePct, maePct, peakMin * 60, troughMin * 60);

        return new Stats(baseline, baselineOffsetMin, discPrice, discPct, endPrice, endMin, endPct,
                mfePct, peakPrice, peakMin, peakTime,
                maePct, troughPrice, troughMin, troughTime, pattern);
    }

    /**
     * 종료 메시지 조립. 모든 %의 기준은 공시 2분 전 가격(라벨로 노출하지 않는 숨은 기준점).
     * 공시 시작 시각·시작가(공시 시점 가격)+기준가 대비 %, 10분 뒤 가격+기준가 대비 %를 한 줄에 보여주고,
     * 고점/저점도 기준가 대비 %와 실제 시계 시각(예: 10:07)으로 표기한다.
     */
    private static String composeMessage(Disclosure d, Stats st, ZonedDateTime t0) {
        return String.format(
                "📊 **공시 후 %d분 주가** | %s — %s\n"
                        + "공시 %s 시작가 %,d원 (%+.1f%%) → %d분 뒤 %,d원 (%+.1f%%)\n"
                        + "🔺 고점 %+.1f%% %,d원 (%s)\n"
                        + "🔽 저점 %+.1f%% %,d원 (%s)\n"
                        + "패턴: %s",
                st.endMin(), d.corpName(), d.reportNm(),
                CLOCK.format(t0.toLocalTime()), st.discPrice(), st.discPct(),
                st.endMin(), st.endPrice(), st.endPct(),
                st.mfePct(), st.peakPrice(), CLOCK.format(st.peakAt()),
                st.maePct(), st.troughPrice(), CLOCK.format(st.troughAt()),
                st.pattern());
    }

    private void persist(Disclosure d, String category, ZonedDateTime t0, Stats st) {
        try {
            ObjectNode o = mapper.createObjectNode();
            o.put("t0", t0.toString());
            o.put("rceptNo", d.rceptNo());
            o.put("corpName", d.corpName());
            o.put("reportNm", d.reportNm());
            o.put("market", d.marketName());
            o.put("category", category);
            o.put("code", d.stockCode());
            o.put("baseWon", st.baseline());            // 기준가(공시 N분 전 종가, % 기준점)
            o.put("baseOffsetMin", st.baselineOffsetMin());
            o.put("discWon", st.discPrice());           // 공시 시점 가격(시작가)
            o.put("discPct", round1(st.discPct()));     // 공시 시점의 기준가 대비(공시 직전 오른 폭)
            o.put("endWon", st.endPrice());
            o.put("endMin", st.endMin());
            o.put("endPct", round1(st.endPct()));
            o.put("mfePct", round1(st.mfePct()));
            o.put("mfeMin", st.peakMin());
            o.put("mfeAt", CLOCK.format(st.peakAt()));
            o.put("mfeWon", st.peakPrice());
            o.put("maePct", round1(st.maePct()));
            o.put("maeMin", st.troughMin());
            o.put("maeAt", CLOCK.format(st.troughAt()));
            o.put("maeWon", st.troughPrice());
            o.put("pattern", st.pattern());
            Files.writeString(storeFile, mapper.writeValueAsString(o) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("주가 통계 저장 실패: {} - {}", d.corpName(), d.reportNm(), e);
        }
    }

    /** 기준가 대비 변동률(%). */
    private static double pct(long price, long base) {
        return base > 0 ? (price - base) * 100.0 / base : 0.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /**
     * 움직임 패턴 분류 — "올랐다 내렸나 / 내렸다 올랐나 / 계속 한 방향인가".
     * 고점·저점이 모두 임계(FLAT_EPS_PCT) 이상 움직였으면 둘 중 먼저 찍은 쪽으로 방향을 정한다.
     */
    static String classify(double mfePct, double maePct, long peakSec, long troughSec) {
        boolean up = mfePct >= FLAT_EPS_PCT;
        boolean down = maePct <= -FLAT_EPS_PCT;
        if (!up && !down) return "횡보";
        if (up && !down) return "계속 상승";
        if (down && !up) return "계속 하락";
        return peakSec <= troughSec ? "올랐다 내림" : "내렸다 오름";
    }

    /** KRX 정규장(09:00~15:30) 밖이면 true — 프리·애프터마켓은 NXT만 거래하므로 통합 분봉이 필요. */
    static boolean nxtSession(LocalTime t) {
        return t.isBefore(REG_OPEN) || !t.isBefore(REG_CLOSE);
    }

    private boolean withinWindow(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(TRACK_OPEN) && !t.isAfter(TRACK_CLOSE);
    }

    /**
     * 공시 후 주가 통계 한 건.
     *
     * @param baseline          기준가(공시 PRE_MIN분 전 봉 종가, 원) — 모든 %의 숨은 기준점
     * @param baselineOffsetMin 기준가 봉이 공시 시점에서 몇 분 전인지(보통 PRE_MIN)
     * @param discPrice         시작가 = 공시 시점(분) 가격(원)
     * @param discPct           시작가의 기준가 대비 변동률(공시 직전 이미 오른 폭, %)
     * @param endPrice          종료가(공시+창 봉 종가, 원)
     * @param endMin            종료 시점까지 경과(분) — 보통 WINDOW_MIN, 마감에 잘리면 그보다 작음
     * @param endPct            공시 후 변동률(종료가의 기준가 대비, %)
     * @param mfePct            최대 상승폭(고점의 기준가 대비, %)
     * @param peakPrice         고점(원)
     * @param peakMin           고점 발생 시점(공시 기준 경과 분)
     * @param peakAt            고점 발생 시계 시각(예: 10:07)
     * @param maePct            최대 낙폭(저점의 기준가 대비, %)
     * @param troughPrice       저점(원)
     * @param troughMin         저점 발생 시점(공시 기준 경과 분)
     * @param troughAt          저점 발생 시계 시각
     * @param pattern           움직임 패턴
     */
    record Stats(long baseline, long baselineOffsetMin, long discPrice, double discPct,
                 long endPrice, long endMin, double endPct,
                 double mfePct, long peakPrice, long peakMin, LocalTime peakAt,
                 double maePct, long troughPrice, long troughMin, LocalTime troughAt, String pattern) {}
}
