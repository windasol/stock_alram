package com.example.dart.kis;

/**
 * 시장(코스피/코스닥) 전체의 외국인·기관 순매수 가집계 스냅샷.
 * KIS '시장별 투자자매매동향(시세)'(TR FHPTJ04030000)의 최신 누적 행에서 읽는다.
 * 금액은 원 단위(음수면 순매도). 종목별 랭킹({@link InvestorFlowItem})과 달리 시장 전체 합계다.
 */
public record MarketInvestorFlow(
        String market,          // "코스피" / "코스닥"
        long foreignNetWon,     // 외국인 순매수(원), 음수=순매도
        long institutionNetWon  // 기관 순매수(원), 음수=순매도
) {}
