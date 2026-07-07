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

    // pickAtOrBefore(candles, target) — "공시 2분 전 가격" 봉 선택(≤target 중 가장 늦은 봉).

    @Test
    void 공시2분전_봉은_target이하_가장_늦은_봉() {
        List<MinuteCandle> candles = List.of(
                c(17, 48, 500, 500, 500, 500), c(17, 49, 510, 510, 510, 510),
                c(17, 50, 520, 520, 520, 520), c(17, 51, 530, 530, 530, 530));
        MinuteCandle base = DisclosurePriceTracker.pickAtOrBefore(candles, LocalTime.of(17, 50));
        assertEquals(520, base.close());   // 17:50 봉
    }

    @Test
    void target이전_봉이_없으면_가장_이른_봉으로_폴백() {
        List<MinuteCandle> candles = List.of(
                c(9, 1, 100, 100, 100, 100), c(9, 2, 110, 110, 110, 110));
        MinuteCandle base = DisclosurePriceTracker.pickAtOrBefore(candles, LocalTime.of(8, 59));
        assertEquals(100, base.close());   // 가장 이른 봉(9:01)
    }

    @Test
    void 분봉이_비면_null() {
        assertNull(DisclosurePriceTracker.pickAtOrBefore(List.of(), LocalTime.of(17, 50)));
    }

    // composeEntryPrice(corpName, base, prdyClose) — 공시 직전 가격 한 줄. %는 전일종가 대비 당일 등락률.

    @Test
    void 공시직전_가격줄은_콤팩트하게_가격_등락률_100만원매수량() {
        String line = DisclosurePriceTracker.composeEntryPrice(
                c(17, 50, 0, 0, 0, 550_000L), 500_000L);   // 55만원, 전일 50만 → +10.0%, 100만÷55만=1주
        assertEquals("550,000원 (+10.0%)\n💰 100만원 ≈ 1주", line);   // 회사명·시각·"가격" 라벨 없음
    }

    @Test
    void 공시직전_가격줄_전일종가_0이면_등락률_생략() {
        String line = DisclosurePriceTracker.composeEntryPrice(
                c(17, 50, 0, 0, 0, 550_000L), 0L);
        assertEquals("550,000원\n💰 100만원 ≈ 1주", line);
    }

    // budgetShares(price) — 100만원 ÷ 가격(내림). 1주가 100만원 초과면 살 수 없음 안내.

    @Test
    void 백만원_매수량은_가격으로_내림한다() {
        assertEquals("💰 100만원 ≈ 28주", DisclosurePriceTracker.budgetShares(35_000L));   // 1,000,000/35,000=28.5→28
        assertEquals("💰 100만원 ≈ 1,000주", DisclosurePriceTracker.budgetShares(1_000L));
    }

    @Test
    void 한주가_100만원_넘으면_초과안내() {
        assertEquals("💰 1주 1,200,000원 (100만원 초과)", DisclosurePriceTracker.budgetShares(1_200_000L));
    }

    // computeStats(candles, t0Min, fromMin, toMin, prdyClose) — 분봉 창에서 통계 산출.
    // 표기 %는 전일종가(prdyClose) 대비 당일 등락률, prdyClose=0이면 기준가 대비로 폴백. 패턴은 항상 기준가 대비.

    @Test
    void 전일종가_0이면_기준가_대비로_폴백() {
        // prdyClose=0 → 표기 %가 기준가(10:03 종가 10,000) 대비로 떨어진다(기존 동작 보존).
        // 공시 후: +2분 고점 10,600(+6%), +8분 저점 9,700(-3%), +10분 종료 9,800(-2%).
        List<MinuteCandle> candles = List.of(
                c(10, 3, 10_000, 10_010, 9_990, 10_000),   // 기준가 봉(공시 2분 전)
                c(10, 5, 10_000, 10_150, 9_990, 10_100),   // 공시 시점
                c(10, 7, 10_100, 10_600, 10_090, 10_500),  // +2분 고점
                c(10, 13, 10_500, 10_510, 9_700, 9_750),   // +8분 저점
                c(10, 15, 9_750, 9_810, 9_760, 9_800));    // +10분 종료

        DisclosurePriceTracker.Stats st = DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15), 0L);

        assertEquals(10_000, st.baseline());
        assertEquals(2, st.baselineOffsetMin());
        assertEquals(10_000, st.discPrice());        // 시작가 = 공시 2분전 종가(baseline)
        assertEquals(0.0, st.discPct(), 0.05);       // baseline 대비 baseline → 0.0 (전일종가 미조회 폴백)
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
    void 표기는_전일종가_대비_등락률_패턴은_기준가_대비_움직임() {
        // 사용자 보고 케이스: 전일 대비 -6%로 빠진 상태에서 공시. 공시 후엔 거의 안 움직임(횡보).
        // 전일종가 200,000 / 기준가(공시 2분 전) 188,000. 공시 후 ±0.2% 안에서만 진동.
        List<MinuteCandle> candles = List.of(
                c(10, 3, 188_000, 188_010, 187_990, 188_000),   // 기준가 봉
                c(10, 5, 188_000, 188_150, 187_990, 188_100),   // 공시 시점(시작가)
                c(10, 7, 188_100, 188_400, 188_090, 188_200),   // 고점 188,400
                c(10, 13, 188_200, 188_210, 187_800, 187_900),  // 저점 187,800
                c(10, 15, 187_900, 188_010, 187_850, 188_000)); // 종료 188,000

        DisclosurePriceTracker.Stats st = DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15), 200_000L);

        // 표기 %는 전일종가 200,000 대비 — +0.x%가 아니라 실제 당일 등락률(-6%대)로 찍혀야 한다.
        assertEquals(200_000, st.prdyClose());
        assertEquals(188_000, st.discPrice());     // 시작가 = 공시 2분전 종가(공시 시점 188,100이 아님)
        assertEquals(-6.0, st.discPct(), 0.05);    // (188,000-200,000)/200,000
        assertEquals(-6.0, st.endPct(), 0.05);     // (188,000-200,000)/200,000
        assertEquals(-5.8, st.mfePct(), 0.05);     // 고점 188,400
        assertEquals(-6.1, st.maePct(), 0.05);     // 저점 187,800
        // 패턴은 기준가(188,000) 대비 움직임으로 — 당일 등락률을 넣었다면 "계속 하락"으로 오분류됐을 것.
        assertEquals("횡보", st.pattern());
    }

    @Test
    void 시작가는_공시시점_가격이_아니라_공시_2분전_종가() {
        // 공시 2분전(10:03) 종가 10,000, 공시 시점(10:05) 종가 10,300으로 서로 다르게 구성.
        // 시작가는 공시 시점(10,300)이 아니라 2분전 종가(10,000)여야 한다.
        List<MinuteCandle> candles = List.of(
                c(10, 3, 10_000, 10_010, 9_990, 10_000),   // 기준가 봉(공시 2분 전) = 시작가
                c(10, 5, 10_100, 10_350, 10_090, 10_300),  // 공시 시점(시작가로 쓰면 안 됨)
                c(10, 15, 10_300, 10_320, 10_280, 10_310)); // 종료

        DisclosurePriceTracker.Stats st = DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15), 0L);

        assertEquals(10_000, st.baseline());
        assertEquals(10_000, st.discPrice());   // 시작가 == 2분전 종가(baseline), 공시 시점 10,300 아님
    }

    @Test
    void 공시_2분전_봉이_없으면_창내_가장_이른_봉을_기준가로() {
        // 10:03 봉이 없음 → 창 내 가장 이른 10:04 봉(종가 9,500)이 기준가, 오프셋 1분.
        List<MinuteCandle> candles = List.of(
                c(10, 4, 9_500, 9_510, 9_490, 9_500),
                c(10, 5, 9_500, 9_700, 9_500, 9_650),
                c(10, 15, 9_650, 9_700, 9_600, 9_680));

        DisclosurePriceTracker.Stats st = DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15), 0L);

        assertEquals(9_500, st.baseline());
        assertEquals(1, st.baselineOffsetMin());
    }

    @Test
    void 창에_봉이_없으면_null() {
        // 모든 봉이 창 밖(11시대) → null.
        List<MinuteCandle> candles = List.of(c(11, 0, 100, 100, 100, 100));
        assertNull(DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15), 0L));
    }

    @Test
    void 공시후_봉이_없으면_null() {
        // 기준가 봉만 있고 공시 시점 이후 봉이 없음 → null.
        List<MinuteCandle> candles = List.of(c(10, 3, 10_000, 10_010, 9_990, 10_000));
        assertNull(DisclosurePriceTracker.computeStats(
                candles, LocalTime.of(10, 5), LocalTime.of(10, 3), LocalTime.of(10, 15), 0L));
    }
}
