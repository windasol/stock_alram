package com.example.dart.econcal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FomcSchedule.within 경계 — 구간에 걸치는 FOMC 결정일만 이벤트로 나오는지 검증(하드코딩 일정). */
class FomcScheduleTest {

    @Test
    void 구간에_걸친_회의만_반환() {
        // 2026-07-29(7월 FOMC 결정일)을 포함하는 구간.
        List<EconEvent> events = FomcSchedule.within(
                LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 1));
        assertEquals(1, events.size());
        EconEvent e = events.get(0);
        assertEquals(EventType.FOMC, e.type());
        assertEquals(LocalDate.of(2026, 7, 29), e.date());
        assertEquals("FOMC 정례회의", e.title());
        assertEquals("금리 결정", e.detail());
    }

    @Test
    void 회의가_없는_구간이면_빈목록() {
        // 7월 회의(29일)와 9월 회의(16일) 사이 — 8월엔 정례회의 없음.
        assertTrue(FomcSchedule.within(
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 20)).isEmpty());
    }

    @Test
    void 양끝_포함_경계() {
        // 결정일이 구간의 정확히 끝일 때도 포함.
        assertEquals(1, FomcSchedule.within(
                LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 29)).size());
    }
}
