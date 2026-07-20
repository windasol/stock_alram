package com.example.dart.econcal.application;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 발송 시각 정렬 로직 — 다음 발송까지 지연 계산(재시작 이중발송 방지의 근거)을 검증한다. */
class EconCalendarPollerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void 발송시각_전이면_오늘_그_시각까지() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 16, 7, 0, 0, 0, KST);
        assertEquals(3600L, EconCalendarPoller.secondsUntil(LocalTime.of(8, 0), now));   // 1시간 후
    }

    @Test
    void 발송시각_지났으면_내일_그_시각까지() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 16, 9, 0, 0, 0, KST);
        assertEquals(23L * 3600L, EconCalendarPoller.secondsUntil(LocalTime.of(8, 0), now));   // 내일 08:00
    }

    @Test
    void 정확히_발송시각이면_내일로() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 16, 8, 0, 0, 0, KST);
        assertEquals(24L * 3600L, EconCalendarPoller.secondsUntil(LocalTime.of(8, 0), now));
    }
}
