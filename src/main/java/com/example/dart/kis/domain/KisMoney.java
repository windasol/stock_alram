package com.example.dart.kis.domain;

/**
 * KIS 표·랭킹 표기용 금액 포맷 — 거래대금·순매수 금액을 "4.2조 / 380억 / 5,000만"처럼 축약한다.
 * (원 단위 상세 표기는 common의 KoreanMoney — 여기는 표 칸에 맞는 컴팩트 표기 전용.)
 */
public final class KisMoney {

    private KisMoney() {}

    /** 거래대금(원)을 "4.2조 / 380억 / 5,000만"처럼 사람이 읽기 쉬운 단위로 표기. */
    public static String formatWon(long won) {
        double eok = won / 100_000_000.0;          // 1억 = 1e8
        if (eok >= 10_000) return String.format("%.1f조", eok / 10_000.0);
        if (eok >= 1) return String.format("%,.0f억", eok);
        return String.format("%,d만", won / 10_000L);
    }

    /** 순매수 거래대금(원)을 부호와 함께 표기 — 음수(순매도)면 '-' 접두, 0이면 0. {@link #formatWon} 재사용. */
    public static String formatNetWon(long won) {
        if (won == 0) return "0";
        String sign = won < 0 ? "-" : "+";
        return sign + formatWon(Math.abs(won));
    }
}
