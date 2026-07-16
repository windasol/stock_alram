package com.example.dart.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoTradeServiceTest {

    @Test
    void qtyFor_예산을_진입가로_나눈_정수주식수() {
        assertEquals(100L, Position.qtyFor(1_000_000L, 10_000L));  // 100만 / 1만 = 100주
        assertEquals(76L, Position.qtyFor(1_000_000L, 13_100L));   // 내림(76.3 → 76)
    }

    @Test
    void qtyFor_예산보다_비싼_고가주는_0주() {
        assertEquals(0L, Position.qtyFor(1_000_000L, 1_200_000L));
        assertEquals(0L, Position.qtyFor(1_000_000L, 0L));   // 가격 0(조회이상)도 0주
    }

    @Test
    void pnlPct_진입가_대비_손익률() {
        assertEquals(0.0, Position.pnlPct(10_000L, 10_000L), 0.0001);
        assertEquals(5.0, Position.pnlPct(10_000L, 10_500L), 0.0001);   // +5% 익절선
        assertEquals(-2.0, Position.pnlPct(10_000L, 9_800L), 0.0001);   // -2% 손절선
    }

    // nxtSession은 common/domain TradingSession으로 통합 — TradingSessionTest에서 검증한다.
}
