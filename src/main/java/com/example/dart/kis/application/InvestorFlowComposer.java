package com.example.dart.kis.application;

import com.example.dart.common.domain.KstTime;
import com.example.dart.common.text.TextTable;
import com.example.dart.kis.domain.Investor;
import com.example.dart.kis.domain.InvestorFlowItem;
import com.example.dart.kis.domain.InvestorPairItem;
import com.example.dart.kis.domain.KisMoney;
import com.example.dart.kis.domain.MarketInvestorFlow;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 외국인·기관 수급 메시지 조립 — {@link InvestorFlowService}의 조회·발송 오케스트레이션에서 분리한 순수 포맷 계층.
 * 모든 함수는 IO·시각 조회 없이 입력만으로 문자열을 만들어(시각은 파라미터 주입) 단위 테스트가 쉽다.
 *
 * <p>Webex 마크다운은 표(| |)를 렌더하지 않으므로, 표는 코드블록(고정폭) 안에 한글 표시폭({@link TextTable})을
 * 맞춰 ASCII로 정렬한다.
 */
final class InvestorFlowComposer {

    /** 외국인·기관 수급 랭킹에서 순매수/순매도 각각 보여줄 종목 수. */
    static final int INVESTOR_FLOW_TOP = 30;
    /** 외국인+기관 동시매매(양매수/양매도)에서 각각 보여줄 종목 수. */
    static final int INVESTOR_PAIR_TOP = 30;
    /** 수급 표 종목명 칸 표시폭(한글=2). "SK하이닉스"·"LG에너지솔루션" 등 대부분 수용. */
    private static final int FLOW_NAME_W = 12;
    /** 수급 표 순매수금액 칸 표시폭(우측정렬). "+1,234억"·"-987억" 등 수용. */
    private static final int FLOW_AMT_W = 9;
    /** 동시매매 표 외국인/기관 금액 칸 표시폭(우측정렬). */
    private static final int PAIR_AMT_W = 10;
    /** 개인 투자자 표시 라벨 — Investor enum(외국인·기관)엔 없어 시장 전체 수급용으로 별도 정의. */
    private static final String INDIVIDUAL_LABEL = "👤 개인";

    private InvestorFlowComposer() {}

    /**
     * 시장 전체(코스피·코스닥) 외국인·기관·개인 순매수 헤드라인 메시지. (순수 함수 — 테스트용)
     * 예: "📊 **시장 수급** | 13:40 (가집계)\n🌍 외국인 -3,200억 · 🏛 기관 +1,500억 · 👤 개인 +1,700억".
     */
    static String composeMarketFlow(List<MarketInvestorFlow> flows, LocalTime time, String tag) {
        return composeMarketFlow(flows, time, tag, null);
    }

    /**
     * 시장 수급 헤드라인. {@code indexLine}이 있으면 제목 줄 아래에 현재 지수(코스피·코스닥) 한 줄을 덧붙여
     * '지수(결과) → 누가 사고파나(수급)'를 한눈에 보이게 한다. null이면 지수 줄 생략.
     */
    static String composeMarketFlow(List<MarketInvestorFlow> flows, LocalTime time, String tag, String indexLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **시장 수급** | %s  (%s)", KstTime.HH_MM.format(time), tag));
        if (indexLine != null) sb.append(String.format("%n%s", indexLine));
        for (MarketInvestorFlow f : flows) {
            String prefix = f.market().isBlank() ? "" : f.market() + "  ";   // 전체(빈 라벨)면 접두어 없음
            sb.append(String.format("%n%s%s %s · %s %s · %s %s",
                    prefix,
                    Investor.FOREIGN.label(), KisMoney.formatNetWon(f.foreignNetWon()),
                    Investor.INSTITUTION.label(), KisMoney.formatNetWon(f.institutionNetWon()),
                    INDIVIDUAL_LABEL, KisMoney.formatNetWon(f.individualNetWon())));
        }
        return sb.toString();
    }

    /**
     * 시황 리포트 facts용 시장 전체 수급 한 줄(컴팩트). 비면 null.
     * 예: "📊 시장 수급 | 외국인 -3,200억·기관 +1,500억·개인 +1,700억". (순수 함수 — 테스트용)
     */
    static String marketFlowLine(List<MarketInvestorFlow> flows) {
        if (flows.isEmpty()) return null;
        String body = flows.stream()
                .map(f -> {
                    String prefix = f.market().isBlank() ? "" : f.market() + " ";   // 전체(빈 라벨)면 접두어 없음
                    return String.format("%s외국인 %s·기관 %s·개인 %s",
                            prefix, KisMoney.formatNetWon(f.foreignNetWon()), KisMoney.formatNetWon(f.institutionNetWon()),
                            KisMoney.formatNetWon(f.individualNetWon()));
                })
                .collect(Collectors.joining(" / "));
        return "📊 시장 수급 | " + body;
    }

    static String composeInvestorFlow(Investor inv, List<InvestorFlowItem> buys,
                                      List<InvestorFlowItem> sells, String session, LocalTime time, String tag) {
        return composeInvestorFlow(inv, buys, sells, session, time, tag, null);
    }

    /**
     * 한 투자자의 순매수 상위(가장 많이 산)·순매도 상위(가장 많이 판)를 좌우 2열로 나란히 둔 표를 만든다.
     * Webex 마크다운은 표(| |)를 렌더하지 않으므로 코드블록(고정폭) 안에 한글 표시폭을 맞춰 ASCII로 정렬한다.
     * 셀은 종목명 + 순매수금액(컴팩트, 등락률 생략). 같은 순위 행에 매수·매도가 함께 온다.
     * {@code indexLine}이 있으면 제목 줄 아래에 현재 지수 헤드라인을 한 줄 덧붙인다. (순수 함수 — 테스트용)
     */
    static String composeInvestorFlow(Investor inv, List<InvestorFlowItem> buys,
                                      List<InvestorFlowItem> sells, String session, LocalTime time, String tag,
                                      String indexLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s **수급 TOP%d** | %s %s  (%s)",
                inv.label(), INVESTOR_FLOW_TOP, session, KstTime.HH_MM.format(time), tag));
        if (indexLine != null) sb.append(String.format("%n%s", indexLine));

        int rows = Math.max(buys.size(), sells.size());
        if (rows == 0) {
            sb.append(String.format("%n```%n(데이터 없음)%n```"));
            return sb.toString();
        }

        int cellW = FLOW_NAME_W + 1 + FLOW_AMT_W;   // 종목명 + 공백 + 금액
        sb.append(String.format("%n```"));
        // 헤더 + 구분선
        sb.append(String.format("%n %s %s %s",
                TextTable.padDisplay("#", 2, false),
                TextTable.padDisplay("매수(많이산)", cellW, true),
                TextTable.padDisplay("매도(많이판)", cellW, true)));
        sb.append(String.format("%n %s %s %s",
                "--", "-".repeat(cellW), "-".repeat(cellW)));
        for (int i = 0; i < rows; i++) {
            String buyCell = i < buys.size() ? flowCell(buys.get(i)) : " ".repeat(cellW);
            String sellCell = i < sells.size() ? flowCell(sells.get(i)) : "";
            sb.append(String.format("%n %s %s %s",
                    TextTable.padDisplay(Integer.toString(i + 1), 2, false), buyCell, sellCell));
        }
        sb.append(String.format("%n```"));
        return sb.toString();
    }

    /** 수급 표 한 칸 — "종목명(좌측정렬)  순매수금액(우측정렬)". */
    private static String flowCell(InvestorFlowItem it) {
        return TextTable.padDisplay(it.name(), FLOW_NAME_W, true)
                + " " + TextTable.padDisplay(KisMoney.formatNetWon(it.netValueWon()), FLOW_AMT_W, false);
    }

    /**
     * 양매수·양매도 종목을 종목 / 외국인 / 기관 3열 표(코드블록)로 정리한 메시지를 만든다. (순수 함수 — 테스트용)
     */
    static String composeInvestorPair(List<InvestorPairItem> dualBuy, List<InvestorPairItem> dualSell,
                                      String session, LocalTime time, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🤝 **외국인+기관 동시매매** | %s %s  (%s)",
                session, KstTime.HH_MM.format(time), tag));
        sb.append(String.format("%n```"));
        appendPairSection(sb, "[양매수 TOP" + INVESTOR_PAIR_TOP + "] 외국인·기관 둘 다 순매수", dualBuy);
        appendPairSection(sb, "[양매도 TOP" + INVESTOR_PAIR_TOP + "] 외국인·기관 둘 다 순매도", dualSell);
        sb.append(String.format("%n```"));
        return sb.toString();
    }

    /** 동시매매 한 섹션 — 헤더 + "순번 종목 외국인 기관" 표. 비면 안내 문구. */
    private static void appendPairSection(StringBuilder sb, String header, List<InvestorPairItem> items) {
        sb.append(String.format("%n%s", header));
        sb.append(String.format("%n %s %s %s %s",
                TextTable.padDisplay("#", 2, false),
                TextTable.padDisplay("종목", FLOW_NAME_W, true),
                TextTable.padDisplay("외국인", PAIR_AMT_W, false),
                TextTable.padDisplay("기관", PAIR_AMT_W, false)));
        if (items.isEmpty()) {
            sb.append(String.format("%n (해당 종목 없음)"));
            return;
        }
        int rank = 1;
        for (InvestorPairItem it : items) {
            sb.append(String.format("%n %s %s %s %s",
                    TextTable.padDisplay(Integer.toString(rank++), 2, false),
                    TextTable.padDisplay(it.name(), FLOW_NAME_W, true),
                    TextTable.padDisplay(KisMoney.formatNetWon(it.frgnWon()), PAIR_AMT_W, false),
                    TextTable.padDisplay(KisMoney.formatNetWon(it.orgnWon()), PAIR_AMT_W, false)));
        }
    }
}
