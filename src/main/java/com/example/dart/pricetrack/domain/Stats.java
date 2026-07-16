package com.example.dart.pricetrack.domain;

import com.example.dart.kis.domain.MinuteCandle;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 공시 후 주가 통계 한 건 + 산출 순수 함수. 모든 %(discPct·endPct·mfePct·maePct)는 전일종가 대비
 * 당일 등락률이다(prdyClose=0이면 기준가 대비로 폴백). 단 pattern은 기준가 대비 움직임으로 분류한 결과다.
 *
 * @param prdyClose         전일종가(원, 표기 %의 기준) — 0이면 기준가로 폴백한 상태
 * @param baseline          기준가(공시 N분 전 봉 종가, 원) — 패턴 분류 기준점
 * @param baselineOffsetMin 기준가 봉이 공시 시점에서 몇 분 전인지
 * @param discPrice         시작가 = 공시 N분 전 봉 종가(원, baseline과 동일)
 * @param discPct           시작가의 당일 등락률(전일종가 대비, %)
 * @param endPrice          종료가(공시+창 봉 종가, 원)
 * @param endMin            종료 시점까지 경과(분) — 보통 추적 창, 마감에 잘리면 그보다 작음
 * @param endPct            종료가의 당일 등락률(전일종가 대비, %)
 * @param mfePct            고점의 당일 등락률(전일종가 대비, %)
 * @param peakPrice         고점(원)
 * @param peakMin           고점 발생 시점(공시 기준 경과 분)
 * @param peakAt            고점 발생 시계 시각(예: 10:07)
 * @param maePct            저점의 당일 등락률(전일종가 대비, %)
 * @param troughPrice       저점(원)
 * @param troughMin         저점 발생 시점(공시 기준 경과 분)
 * @param troughAt          저점 발생 시계 시각
 * @param pattern           움직임 패턴(기준가 대비 움직임으로 분류)
 */
public record Stats(long prdyClose, long baseline, long baselineOffsetMin, long discPrice, double discPct,
                    long endPrice, long endMin, double endPct,
                    double mfePct, long peakPrice, long peakMin, LocalTime peakAt,
                    double maePct, long troughPrice, long troughMin, LocalTime troughAt, String pattern) {

    /** 횡보/유의미 변동을 가르는 임계(%). 이보다 작은 변동은 "안 움직였다"로 본다. */
    public static final double FLAT_EPS_PCT = 0.5;

    /**
     * 분봉 목록에서 통계를 낸다(순수 함수 — 네트워크 분리).
     *
     * 표기 %(시작가·종료가·고점·저점)는 <b>전일종가(prdyClose) 대비 당일 등락률</b>이다 — 사용자가 종목 화면에서
     * 보는 그 %. prdyClose가 0(조회 실패)이면 기준가 대비로 폴백한다.
     *
     * 단, 패턴 분류는 전일종가가 아닌 <b>기준가(공시 N분 전 종가) 대비 움직임</b>으로 본다 — {@link #classify}의
     * 임계(±{@value #FLAT_EPS_PCT}%)에 -6%대 당일 등락률을 그대로 넣으면 멀쩡한 횡보가 "계속 하락"으로
     * 오분류되기 때문. 즉 "지금 얼마인지(표기)"와 "공시 후 어떻게 움직였는지(패턴)"의 기준점을 분리한다.
     *
     * 기준가 = 공시 N분 전 봉의 종가(없으면 창 내 가장 이른 봉). 고점/저점/종료가는
     * 공시 시점 이후 구간 [t0Min, toMin]에서만 본다(공시 전 봉은 기준가용일 뿐).
     *
     * @param prdyClose 전일종가(원). 0이면 기준가 대비로 폴백.
     * @return 통계. 창에 봉이 없거나 공시 후 봉이 없으면 null(분석 생략).
     */
    public static Stats compute(List<MinuteCandle> candles, LocalTime t0Min, LocalTime fromMin, LocalTime toMin,
                                long prdyClose) {
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

        long discPrice = baseline;   // 시작가 = 공시 N분 전 봉 종가(공시 시점 가격이 아니라 추적 출발점)

        // 고점/저점 스캔의 출발점을 시작가(baseline)로 잡는다 — 이렇게 해야 "저점 ≤ 시작가 ≤ 고점"이
        // 항상 성립한다. 시작가를 빼고 공시 후 봉만 보면, 시작가보다 높은 지점이 "저점"으로 찍혀
        // (예: 시작가 -6.0% 인데 저점 -4.9%) 헤더와 모순되는 알림이 나간다.
        long peakPrice = discPrice, troughPrice = discPrice;
        LocalTime peakTime = baseEntry.getKey(), troughTime = baseEntry.getKey();
        for (Map.Entry<LocalTime, MinuteCandle> e : post.entrySet()) {
            MinuteCandle c = e.getValue();
            long hi = c.high() > 0 ? c.high() : c.close();   // 고/저가 누락 봉은 종가로 대체
            long lo = c.low() > 0 ? c.low() : c.close();
            if (hi > peakPrice) { peakPrice = hi; peakTime = e.getKey(); }
            if (lo < troughPrice) { troughPrice = lo; troughTime = e.getKey(); }
        }

        long peakMin = ChronoUnit.MINUTES.between(t0Min, peakTime);
        long troughMin = ChronoUnit.MINUTES.between(t0Min, troughTime);

        // 표기 %의 기준 = 전일종가(당일 등락률). 조회 실패(0)면 기준가로 폴백.
        long dispBase = prdyClose > 0 ? prdyClose : baseline;
        double discPct = pct(discPrice, dispBase);
        double endPct = pct(endPrice, dispBase);
        double mfePct = pct(peakPrice, dispBase);
        double maePct = pct(troughPrice, dispBase);

        // 패턴 분류는 공시 직전(기준가) 대비 움직임으로 — 당일 등락률을 넣으면 임계가 깨진다.
        String pattern = classify(pct(peakPrice, baseline), pct(troughPrice, baseline),
                pct(endPrice, baseline), peakMin * 60, troughMin * 60);

        return new Stats(prdyClose, baseline, baselineOffsetMin, discPrice, discPct, endPrice, endMin, endPct,
                mfePct, peakPrice, peakMin, peakTime,
                maePct, troughPrice, troughMin, troughTime, pattern);
    }

    /**
     * 움직임 패턴 분류 — "올랐다 내렸나 / 내렸다 올랐나 / 계속 한 방향인가".
     *
     * 기준가(공시 N분 전) 대비 고점·저점만으로 보면, 고점 찍고 기준가 근처로 되돌려도 기준가를 안 깨면
     * "계속 상승"으로 잘못 잡힌다. 그래서 종료가가 고점에서 임계({@value #FLAT_EPS_PCT}) 이상 되돌렸으면(pullback)
     * "올랐다 내림", 저점에서 그만큼 되올라왔으면(bounce) "내렸다 오름"으로 본다.
     *  - 기준가 양쪽을 다 건드린 경우(up&&down): 먼저 찍은 극점으로 방향 결정(기존과 동일).
     *  - 한쪽만 건드린 경우: 그 극점에서 종료가가 얼마나 되돌렸는지로 반전 여부 판정.
     */
    public static String classify(double mfePct, double maePct, double endPct, long peakSec, long troughSec) {
        boolean up = mfePct >= FLAT_EPS_PCT;
        boolean down = maePct <= -FLAT_EPS_PCT;
        if (!up && !down) return "횡보";
        if (up && down) return peakSec <= troughSec ? "올랐다 내림" : "내렸다 오름";
        if (up) return (mfePct - endPct) >= FLAT_EPS_PCT ? "올랐다 내림" : "계속 상승";
        return (endPct - maePct) >= FLAT_EPS_PCT ? "내렸다 오름" : "계속 하락";
    }

    /** 기준가 대비 변동률(%). */
    private static double pct(long price, long base) {
        return base > 0 ? (price - base) * 100.0 / base : 0.0;
    }
}
