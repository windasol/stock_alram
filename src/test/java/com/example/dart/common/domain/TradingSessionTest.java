package com.example.dart.common.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 세션 판정 — 주가추적·자동매매 2벌로 중복돼 있던 nxtSession을 통합한 뒤의 단일 검증. */
class TradingSessionTest {

    @Test
    void 정규장_안이면_NXT세션_아님() {
        assertFalse(TradingSession.nxtSession(LocalTime.of(9, 0)));    // 09:00 개장
        assertFalse(TradingSession.nxtSession(LocalTime.of(10, 5)));
        assertFalse(TradingSession.nxtSession(LocalTime.of(14, 0)));   // 정규장 중
    }

    @Test
    void 프리_애프터마켓이면_NXT세션() {
        assertTrue(TradingSession.nxtSession(LocalTime.of(8, 30)));    // 프리마켓
        assertTrue(TradingSession.nxtSession(LocalTime.of(15, 30)));   // 정규장 종료 = 애프터마켓 시작
        assertTrue(TradingSession.nxtSession(LocalTime.of(18, 0)));    // 애프터마켓
    }

    @Test
    void 연장_포함_거래시간은_08시부터_20시까지_경계_포함() {
        assertFalse(TradingSession.withinExtendedHours(LocalTime.of(7, 59)));
        assertTrue(TradingSession.withinExtendedHours(LocalTime.of(8, 0)));
        assertTrue(TradingSession.withinExtendedHours(LocalTime.of(13, 0)));
        assertTrue(TradingSession.withinExtendedHours(LocalTime.of(20, 0)));
        assertFalse(TradingSession.withinExtendedHours(LocalTime.of(20, 1)));
    }
}
