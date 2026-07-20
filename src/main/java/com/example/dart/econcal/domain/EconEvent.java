package com.example.dart.econcal.domain;

import java.time.LocalDate;

/**
 * 캘린더 이벤트 1건 — 종류·날짜·제목·부가정보. 소스(FRED·Finnhub·FOMC 하드코딩)가 이 공통 형태로 정규화해
 * 담고, {@link EconCalendarComposer}가 날짜별로 묶어 렌더링한다. 순수 값 객체(IO·로깅 없음).
 *
 * @param type   이벤트 종류(정렬·아이콘 결정)
 * @param date   발표/회의 날짜
 * @param title  표시 제목(예: "소비자물가(CPI)", "ASML 실적", "FOMC 정례회의")
 * @param detail 괄호로 덧붙일 부가정보(예: 실적 발표 시점 "장전"·"장마감후", FOMC "금리 결정"). 없으면 빈 문자열
 */
public record EconEvent(EventType type, LocalDate date, String title, String detail) {

    public EconEvent {
        detail = (detail == null) ? "" : detail;
    }

    public static EconEvent of(EventType type, LocalDate date, String title) {
        return new EconEvent(type, date, title, "");
    }
}
