package com.example.dart.service;

import com.example.dart.model.Disclosure;
import com.example.dart.notify.Notifier;
import com.example.dart.quote.StockQuoteClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 호재 공시 감지 시각(t0)을 기준으로 종목 주가를 10분간 추적해 "공시 후 어떻게 움직였나"를 통계로 남긴다.
 *
 * 흐름: t0에 기준가를 잡고 10초 간격으로 현재가를 샘플링 → 종료 시 최대상승폭(MFE)·최대낙폭(MAE)·
 * 고점/저점 시각·패턴(계속상승/계속하락/올랐다내림/내렸다오름/횡보)을 계산해 채널 메시지로 보내고
 * JSONL 파일에 누적한다(나중에 집계용).
 *
 * 추적 시간대는 NXT 연장세션을 포함한 08:00~20:00(평일). 그 밖(마감 후·새벽·주말)에 뜬 공시는 가격이
 * 움직이지 않아 의미가 없으므로 건너뛴다. 마감(20:00)에 걸리면 그 시점까지만 샘플링한다.
 *
 * 종목코드가 없는 공시(코넥스·비상장 등)는 추적 대상이 아니다.
 */
public class DisclosurePriceTracker {

    private static final Logger log = LoggerFactory.getLogger(DisclosurePriceTracker.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 추적 운영시간 — NXT 프리마켓 08:00 ~ 애프터마켓 20:00. 이 창 밖이면 추적하지 않는다. */
    private static final LocalTime TRACK_OPEN = LocalTime.of(8, 0);
    private static final LocalTime TRACK_CLOSE = LocalTime.of(20, 0);

    private static final int WINDOW_MIN = 10;            // 추적 창
    private static final int SAMPLE_INTERVAL_SEC = 10;   // 샘플 간격
    /** 횡보/유의미 변동을 가르는 임계(%). 이보다 작은 변동은 "안 움직였다"로 본다. */
    private static final double FLAT_EPS_PCT = 0.5;

    private final StockQuoteClient quoteClient;
    private final Notifier notifier;
    private final Path storeFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService pool =
            Executors.newScheduledThreadPool(2, r -> new Thread(r, "price-tracker"));

    public DisclosurePriceTracker(StockQuoteClient quoteClient, Notifier notifier, Path storeFile) {
        this.quoteClient = quoteClient;
        this.notifier = notifier;
        this.storeFile = storeFile;
    }

    /**
     * 공시 1건 추적을 시작한다(비동기). 종목코드가 없거나 추적 시간대가 아니면 조용히 건너뛴다.
     * 기준가 조회(네트워크)는 폴링 루프를 막지 않도록 추적 풀에서 수행한다.
     */
    public void track(Disclosure d, String category) {
        String code = d.stockCode();
        if (code == null || code.isBlank()) return;   // 코넥스·비상장 등 — 추적 불가
        if (!withinWindow(ZonedDateTime.now(KST))) {
            log.info("주가 추적 생략(장외): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        pool.submit(() -> start(d, category));
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

    private void start(Disclosure d, String category) {
        OptionalLong base = quoteClient.currentPriceWon(d.stockCode());
        if (base.isEmpty()) {
            log.warn("주가 추적 생략(기준가 조회 실패): {} {} - {}", d.stockCode(), d.corpName(), d.reportNm());
            return;
        }
        Tracking t = new Tracking(d, category, ZonedDateTime.now(KST), base.getAsLong());
        t.samples.add(new long[]{0, base.getAsLong()});
        log.info("주가 추적 시작 [{}] 기준가 {}원: {} - {}",
                d.stockCode(), base.getAsLong(), d.corpName(), d.reportNm());
        scheduleNext(t);
    }

    private void scheduleNext(Tracking t) {
        pool.schedule(() -> sample(t), SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** 한 번 샘플링한다. 자기 자신을 재예약하므로 같은 추적의 샘플 리스트는 직렬로만 접근된다(동시성 안전). */
    private void sample(Tracking t) {
        try {
            ZonedDateTime now = ZonedDateTime.now(KST);
            long elapsed = Duration.between(t.t0, now).getSeconds();
            quoteClient.currentPriceWon(t.d.stockCode())
                    .ifPresent(v -> t.samples.add(new long[]{elapsed, v}));
            if (elapsed >= WINDOW_MIN * 60L || !withinWindow(now)) {
                finish(t, elapsed);
            } else {
                scheduleNext(t);
            }
        } catch (Exception e) {
            log.warn("주가 샘플링 오류 — 추적 종료: {} - {}", t.d.corpName(), t.d.reportNm(), e);
            finish(t, Duration.between(t.t0, ZonedDateTime.now(KST)).getSeconds());
        }
    }

    private void finish(Tracking t, long elapsedSec) {
        if (t.samples.size() < 2) {
            log.info("주가 추적 표본 부족 — 통계 생략: {} - {}", t.d.corpName(), t.d.reportNm());
            t.samples.clear();
            return;
        }
        long p0 = t.baseline;
        long[] last = t.samples.get(t.samples.size() - 1);
        long peakPrice = p0, troughPrice = p0, peakSec = 0, troughSec = 0;
        for (long[] s : t.samples) {
            if (s[1] > peakPrice) { peakPrice = s[1]; peakSec = s[0]; }
            if (s[1] < troughPrice) { troughPrice = s[1]; troughSec = s[0]; }
        }
        double endPct = pct(last[1], p0);
        double mfePct = pct(peakPrice, p0);    // 최대 상승폭(>=0)
        double maePct = pct(troughPrice, p0);  // 최대 낙폭(<=0)
        String pattern = classify(mfePct, maePct, peakSec, troughSec);

        log.info("주가 추적 종료 [{}] {} — 종료 {}% / 고점 {}%({}s) / 저점 {}%({}s) / 패턴 {}",
                t.d.stockCode(), t.d.corpName(),
                round1(endPct), round1(mfePct), peakSec, round1(maePct), troughSec, pattern);

        notifier.send(composeMessage(t, last[1], elapsedSec, endPct,
                mfePct, peakSec, peakPrice, maePct, troughSec, troughPrice, pattern));
        persist(t, endPct, mfePct, peakSec, peakPrice, maePct, troughSec, troughPrice, pattern);

        // 알림·저장을 마쳤으면 인메모리 시계열을 즉시 비운다 — 완료된 추적이 메모리를 붙들고 있지 않게 한다.
        // (Tracking 자체도 이 메서드 종료 후 더 참조되지 않아 GC 대상이 되지만, 시계열은 명시적으로 즉시 반환.)
        t.samples.clear();
    }

    private String composeMessage(Tracking t, long endPrice, long elapsedSec,
                                  double endPct, double mfePct, long peakSec, long peakPrice,
                                  double maePct, long troughSec, long troughPrice, String pattern) {
        return String.format(
                "📊 **공시 후 %s 주가** | %s — %s\n"
                        + "시작 %,d원 → 종료 %,d원 (%+.1f%%)\n"
                        + "🔺 고점 %+.1f%% %,d원 (%s) · 🔽 저점 %+.1f%% %,d원 (%s)\n"
                        + "패턴: %s",
                formatDuration(elapsedSec), t.d.corpName(), t.d.reportNm(),
                t.baseline, endPrice, endPct,
                mfePct, peakPrice, formatDuration(peakSec), maePct, troughPrice, formatDuration(troughSec),
                pattern);
    }

    /** 경과 초를 "2분 10초"처럼 표기. 60초 미만이면 "40초", 정확히 분이면 "3분". */
    static String formatDuration(long sec) {
        long m = sec / 60, s = sec % 60;
        if (m == 0) return s + "초";
        return s == 0 ? m + "분" : m + "분 " + s + "초";
    }

    private void persist(Tracking t, double endPct, double mfePct, long peakSec, long peakPrice,
                         double maePct, long troughSec, long troughPrice, String pattern) {
        try {
            ObjectNode o = mapper.createObjectNode();
            o.put("t0", t.t0.toString());
            o.put("rceptNo", t.d.rceptNo());
            o.put("corpName", t.d.corpName());
            o.put("reportNm", t.d.reportNm());
            o.put("market", t.d.marketName());
            o.put("category", t.category);
            o.put("code", t.d.stockCode());
            o.put("baseWon", t.baseline);
            o.put("endPct", round1(endPct));
            o.put("mfePct", round1(mfePct));
            o.put("mfeSec", peakSec);
            o.put("mfeWon", peakPrice);
            o.put("maePct", round1(maePct));
            o.put("maeSec", troughSec);
            o.put("maeWon", troughPrice);
            o.put("pattern", pattern);
            ArrayNode series = o.putArray("series");
            for (long[] s : t.samples) {
                ArrayNode pair = series.addArray();
                pair.add(s[0]);
                pair.add(s[1]);
            }
            Files.writeString(storeFile, mapper.writeValueAsString(o) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("주가 통계 저장 실패: {} - {}", t.d.corpName(), t.d.reportNm(), e);
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
     * 움직임 패턴 분류 — 사용자가 보고 싶어한 "올랐다 내렸나 / 내렸다 올랐나 / 계속 한 방향인가".
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

    private boolean withinWindow(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(TRACK_OPEN) && !t.isAfter(TRACK_CLOSE);
    }

    /** 추적 중인 공시 1건의 상태 — 같은 추적은 직렬 샘플링이라 별도 동기화 없이 안전. */
    private static final class Tracking {
        final Disclosure d;
        final String category;
        final ZonedDateTime t0;
        final long baseline;
        final List<long[]> samples = new ArrayList<>();

        Tracking(Disclosure d, String category, ZonedDateTime t0, long baseline) {
            this.d = d;
            this.category = category;
            this.t0 = t0;
            this.baseline = baseline;
        }
    }
}
