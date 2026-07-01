package com.example.dart.kis;

/**
 * 시장 전체의 개인·외국인·기관 순매수 가집계 스냅샷.
 * KIS '시장별 투자자매매동향(시세)'(TR FHPTJ04030000, 표준 호출 999/S001)의 최신 누적 행에서 읽는다.
 * 금액은 원 단위(음수면 순매도). 종목별 랭킹({@link InvestorFlowItem})과 달리 시장 전체 합계다.
 */
public record MarketInvestorFlow(
        String market,          // 표시용 시장 라벨(시장 전체면 빈 문자열)
        long foreignNetWon,     // 외국인 순매수(원), 음수=순매도
        long institutionNetWon, // 기관 순매수(원), 음수=순매도
        long individualNetWon   // 개인 순매수(원), 음수=순매도 (외국인·기관의 반대편 수급)
) {}
