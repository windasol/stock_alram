package com.example.dart.service;

import com.example.dart.kis.MinuteCandle;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisclosurePriceTrackerTest {

    private static MinuteCandle c(int h, int m, long open, long high, long low, long close) {
        return new MinuteCandle(LocalTime.of(h, m), open, high, low, close);
    }

    // classify(mfePct, maePct, endPct, peakSec, troughSec) — mfe>=0(최대상승), mae<=0(최대낙폭)

    @Test
    void 변동_거의_없으면_횡보() {
        assertEquals("횡보", DisclosurePriceTracker.classify(0.3, -0.2, 0.1, 60, 120));
    }

    @Test
    void 올라서_고점_근처로_끝나면_계속_상승() {
        // 고점 +4.0%, 종료 +3.9% — 고점에서 거의 안 내려옴.
        assertEquals("계속 상승", DisclosurePriceTracker.classify(4.0, -0.1, 3.9, 300, 30));
    }

    @Test
    void 내려서_저점_근처로_끝나면_계속_하락() {
        assertEquals("계속 하락", DisclosurePriceTracker.classify(0.2, -3.5, -3.4, 30, 300));
    }

    @Test
    void 고점이_저점보다_먼저면_올랐다_내림() {
        assertEquals("올랐다 내림", DisclosurePriceTracker.classify(5.0, -2.0, -1.0, 120, 480));
    }

    @Test
    void 저점이_고점보다_먼저면_내렸다_오름() {
        assertEquals("내렸다 오름", DisclosurePriceTracker.classify(3.0, -4.0, 0.5, 480, 120));
    }

    @Test
    void 기준가_안깨도_고점에서_되돌리면_올랐다_내림() {
        // 우진아이엔에스 케이스: 고점 +5.4% 찍고 +0.6%로 복귀(기준가 밑으론 안 감). 계속 상승이 아님.
        assertEquals("올랐다 내림", DisclosurePriceTracker.classify(5.4, 0.0, 0.6, 0, 60));
    }

    // nxtSession(t) — 정규장 밖(프리·애프터마켓)이면 통합("UN") 분봉이 필요

    @Test
    void 정규장_안이면_NXT세션_아님() {
        assertFalse(DisclosurePriceTracker.nxtSession(LocalTime.of(10, 5)));
        assertFalse(DisclosurePriceTracker.nxtSession(LocalTime.of(9, 0)));   // 09:00 개장
    }

    @Test
    void 프리_애프터마켓이면_NXT세션() {
        assertTrue(DisclosurePriceTracker.nxtSession(LocalTime.of(8, 30)));   // 프리마켓
        assertTrue(DisclosurePriceTracker.nxtSession(LocalTime.of(15, 30)));  // 애프터마켓 시작
        assertTrue(DisclosurePriceTracker.nxtSession(LocalTime.of(18, 0)));   // 애프터마켓
    }

    // computeStats(candles, t0Min, fromMin, toMin) — 분봉 창에서 통계 산출

    @Test
    void 기준가는_공시_2분_전_종가_고점저점은_공시후_구간() {
        // 공시 10:05. 창 [10:03 ~ 10:15]. 기준가 = 10:03 종가 10,000.
        // 공시 후: +2분 고점 10,600(+6%), +8분 저점 9,700(-3%), +10분 종료 9,800(-2%).
        List<MinuteCandle> candles = List.of(
                c(10, 3, 10_000, 10_010, 9_990, 10_000),   // 기준가 봉(공시 2분 전)
                c(10, 5, 10_000, 10_150, 9_990, 10_100),   // 공시 시점
                c(10, 7, 10_100, 10_600, 10_090, 10_500),  // +2분 고점
                c(10, 13, 10_500, 10_510, 9_700, 9_750),   // +8분 저점
                c(10, 15, 9_750, 9_810, 9_760, 9_800));    // +10분 종료

        DisclosurePriceTracker.Stats st = DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15));

        assertEquals(10_000, st.baseline());
        assertEquals(2, st.baselineOffsetMin());
        assertEquals(10_100, st.discPrice());        // 공시 시점 가격(10:05 종가)
        assertEquals(1.0, st.discPct(), 0.05);       // 기준가 10,000 대비 +1.0%
        assertEquals(9_800, st.endPrice());
        assertEquals(10, st.endMin());
        assertEquals(-2.0, st.endPct(), 0.05);
        assertEquals(6.0, st.mfePct(), 0.05);
        assertEquals(10_600, st.peakPrice());
        assertEquals(2, st.peakMin());
        assertEquals(LocalTime.of(10, 7), st.peakAt());
        assertEquals(-3.0, st.maePct(), 0.05);
        assertEquals(9_700, st.troughPrice());
        assertEquals(8, st.troughMin());
        assertEquals(LocalTime.of(10, 13), st.troughAt());
        assertEquals("올랐다 내림", st.pattern());
    }

    @Test
    void 공시_2분전_봉이_없으면_창내_가장_이른_봉을_기준가로() {
        // 10:03 봉이 없음 → 창 내 가장 이른 10:04 봉(종가 9,500)이 기준가, 오프셋 1분.
        List<MinuteCandle> candles = List.of(
                c(10, 4, 9_500, 9_510, 9_490, 9_500),
                c(10, 5, 9_500, 9_700, 9_500, 9_650),
                c(10, 15, 9_650, 9_700, 9_600, 9_680));

        DisclosurePriceTracker.Stats st = DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15));

        assertEquals(9_500, st.baseline());
        assertEquals(1, st.baselineOffsetMin());
    }

    @Test
    void 창에_봉이_없으면_null() {
        // 모든 봉이 창 밖(11시대) → null.
        List<MinuteCandle> candles = List.of(c(11, 0, 100, 100, 100, 100));
        assertNull(DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15)));
    }

    @Test
    void 공시후_봉이_없으면_null() {
        // 기준가 봉만 있고 공시 시점 이후 봉이 없음 → null.
        List<MinuteCandle> candles = List.of(c(10, 3, 10_000, 10_010, 9_990, 10_000));
        assertNull(DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15)));
    }
}
