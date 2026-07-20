package com.example.dart.econcal.domain;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 미 연준 FOMC 정례회의 일정 — 연 8회 고정 공표라 별도 API 없이 하드코딩한다(무료 API 중 FOMC를 안정적으로
 * 주는 곳이 마땅치 않고, 날짜가 1년 전 미리 확정 발표되므로 이 방식이 가장 견고하다).
 *
 * <p>여기 담는 날짜는 각 회의의 <b>둘째 날(금리 결정·성명 발표일)</b>이다 — 시장이 반응하는 날이 그날이다.
 * <b>매년 갱신 필요</b>: 연준이 다음 해 일정을 공표하면(보통 전년도 중순) 아래 상수에 해당 연도를 추가한다.
 * 출처: federalreserve.gov/monetarypolicy/fomccalendars.htm
 */
public final class FomcSchedule {

    private FomcSchedule() {}

    /** 2026년 FOMC 정례회의 금리 결정일(둘째 날). 연준 공표 기준. */
    private static final List<LocalDate> DECISION_DATES_2026 = List.of(
            LocalDate.of(2026, Month.JANUARY, 28),
            LocalDate.of(2026, Month.MARCH, 18),
            LocalDate.of(2026, Month.APRIL, 29),
            LocalDate.of(2026, Month.JUNE, 17),
            LocalDate.of(2026, Month.JULY, 29),
            LocalDate.of(2026, Month.SEPTEMBER, 16),
            LocalDate.of(2026, Month.OCTOBER, 28),
            LocalDate.of(2026, Month.DECEMBER, 9));

    private static final List<LocalDate> ALL = DECISION_DATES_2026;

    /**
     * {@code [from, to]}(양끝 포함) 구간에 걸치는 FOMC 결정일을 이벤트로 돌려준다.
     * 순수 함수 — 오늘 날짜는 호출자(폴러)가 파라미터로 넘긴다.
     */
    public static List<EconEvent> within(LocalDate from, LocalDate to) {
        List<EconEvent> events = new ArrayList<>();
        for (LocalDate d : ALL) {
            if (!d.isBefore(from) && !d.isAfter(to)) {
                events.add(new EconEvent(EventType.FOMC, d, "FOMC 정례회의", "금리 결정"));
            }
        }
        return events;
    }
}
