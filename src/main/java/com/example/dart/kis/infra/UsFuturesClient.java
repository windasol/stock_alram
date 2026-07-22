package com.example.dart.kis.infra;

import java.util.ArrayList;
import java.util.List;

/**
 * 미국 주가지수 선물의 전일 대비 등락률을 조회한다(야후 차트 API — {@link YahooChartClient}).
 * 장 흐름 리포트에 '대외(미국 선물) 여건' 한 줄을 더해, LLM이 국내 자금 흐름과 엮어 서술하도록 돕는다.
 *
 * 국내 장중엔 미국 현물장이 닫혀 있어 선물이 실시간 대외 분위기의 프록시다. 키 불필요(비공식·무료).
 * 일부 지수 실패는 건너뛰고, 전부 실패면 null을 반환해 리포트는 국내 데이터로 계속된다(앱을 멈추지 않는다).
 */
public class UsFuturesClient {

    /** 조회 대상 선물 — 야후 심볼과 표시 라벨. (S&P500·나스닥100·다우 e-mini 선물) */
    private static final List<Symbol> SYMBOLS = List.of(
            new Symbol("ES=F", "S&P"),
            new Symbol("NQ=F", "나스닥"),
            new Symbol("YM=F", "다우"));

    private final YahooChartClient yahoo;

    public UsFuturesClient() {
        this.yahoo = new YahooChartClient();
    }

    /**
     * 미국 선물 등락률 한 줄(예: "🌎 **미국 선물** | S&P +0.4%, 나스닥 +0.6%, 다우 +0.2%").
     * 일부 실패는 건너뛰고, 전부 실패(미조회·차단 등)면 null — 리포트가 이 블록을 생략한다.
     */
    public String summaryLine() {
        List<Quote> quotes = new ArrayList<>();
        for (Symbol s : SYMBOLS) {
            yahoo.fetch(s.code()).ifPresent(snap -> quotes.add(new Quote(s.label(), snap.pct())));
        }
        return formatSummary(quotes);
    }

    /** 등락률 목록을 "🌎 **미국 선물** | S&P +0.4%, ..." 한 줄로. 빈 목록이면 null. (순수 함수 — 테스트용) */
    static String formatSummary(List<Quote> quotes) {
        if (quotes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("🌎 **미국 선물** | ");
        for (int i = 0; i < quotes.size(); i++) {
            if (i > 0) sb.append(", ");
            Quote q = quotes.get(i);
            sb.append(String.format("%s %+.1f%%", q.label(), q.pct()));
        }
        return sb.toString();
    }

    /** 야후 심볼 ↔ 표시 라벨. */
    private record Symbol(String code, String label) {}

    /** 표시 라벨 + 등락률(%). */
    record Quote(String label, double pct) {}
}
