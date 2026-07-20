package com.example.dart.econcal.domain;

/**
 * 경제/실적 캘린더 이벤트 종류 — 표시 아이콘과 하루 안에서의 정렬 우선순위(enum 선언 순서)를 함께 정의한다.
 * 매크로(FOMC·지표)를 개별 기업 실적보다 위에 보이도록 FOMC → 지표 → 실적 순으로 선언한다.
 */
public enum EventType {
    /** 미 연준 FOMC 정례회의(금리 결정). */
    FOMC("🏛️"),
    /** 경제지표 발표(CPI·PPI·고용·GDP 등). */
    ECONOMIC("📊"),
    /** 기업 실적 발표. */
    EARNINGS("🏢");

    private final String icon;

    EventType(String icon) {
        this.icon = icon;
    }

    public String icon() {
        return icon;
    }
}
