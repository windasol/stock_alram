package com.example.dart.trade;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTradeServiceTest {

    @Test
    void qtyFor_예산을_진입가로_나눈_정수주식수() {
        assertEquals(100L, AutoTradeService.qtyFor(1_000_000L, 10_000L));  // 100만 / 1만 = 100주
        assertEquals(76L, AutoTradeService.qtyFor(1_000_000L, 13_100L));   // 내림(76.3 → 76)
    }

    @Test
    void qtyFor_예산보다_비싼_고가주는_0주() {
        assertEquals(0L, AutoTradeService.qtyFor(1_000_000L, 1_200_000L));
        assertEquals(0L, AutoTradeService.qtyFor(1_000_000L, 0L));   // 가격 0(조회이상)도 0주
    }

    @Test
    void pnlPct_진입가_대비_손익률() {
        assertEquals(0.0, AutoTradeService.pnlPct(10_000L, 10_000L), 0.0001);
        assertEquals(5.0, AutoTradeService.pnlPct(10_000L, 10_500L), 0.0001);   // +5% 익절선
        assertEquals(-2.0, AutoTradeService.pnlPct(10_000L, 9_800L), 0.0001);   // -2% 손절선
    }

    @Test
    void nxtSession_정규장_밖만_true() {
        assertTrue(AutoTradeService.nxtSession(LocalTime.of(8, 30)));    // 프리마켓
        assertFalse(AutoTradeService.nxtSession(LocalTime.of(9, 0)));    // 정규장 시작
        assertFalse(AutoTradeService.nxtSession(LocalTime.of(14, 0)));   // 정규장 중
        assertTrue(AutoTradeService.nxtSession(LocalTime.of(15, 30)));   // 정규장 종료 = 애프터마켓
        assertTrue(AutoTradeService.nxtSession(LocalTime.of(18, 0)));    // 애프터마켓
    }
}
