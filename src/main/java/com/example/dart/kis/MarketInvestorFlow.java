package com.example.dart.kis;

/**
 * 시장 전체(코스피·코스닥)의 개인·외국인·기관 순매수 스냅샷.
 * 네이버 실시간 지수 투자자 트렌드({@link com.example.dart.market.DomesticMarketClient#investorFlows()})에서 읽어
 * 매핑한다 — KIS 오픈API는 코스피만 분리해 주지 못해 값이 계속 틀렸다.
 * 금액은 원 단위(음수면 순매도). 종목별 랭킹({@link InvestorFlowItem})과 달리 시장 전체 합계다.
 */
public record MarketInvestorFlow(
        String market,          // 표시용 시장 라벨(시장 전체면 빈 문자열)
        long foreignNetWon,     // 외국인 순매수(원), 음수=순매도
        long institutionNetWon, // 기관 순매수(원), 음수=순매도
        long individualNetWon   // 개인 순매수(원), 음수=순매도 (외국인·기관의 반대편 수급)
) {}
