package com.example.dart.kis;

/**
 * 외국인·기관 매매종목가집계(foreign-institution-total) 한 행에서 두 투자자의 순매수 거래대금을 함께 담는다.
 * 외국인+기관 동시매매(양매수/양매도) 판정·집계에 쓴다.
 *
 * @param code      종목코드 (mksc_shrn_iscd)
 * @param name      종목명 (hts_kor_isnm)
 * @param frgnWon   외국인 순매수 거래대금 원 (frgn_ntby_tr_pbmn, 백만원→원 환산; 음수=순매도)
 * @param orgnWon   기관계 순매수 거래대금 원 (orgn_ntby_tr_pbmn, 백만원→원 환산; 음수=순매도)
 * @param changePct 전일 대비 등락률 % (prdy_ctrt)
 */
public record InvestorPairItem(
        String code,
        String name,
        long frgnWon,
        long orgnWon,
        double changePct
) {
    /** 외국인+기관 합산 순매수 거래대금(원). */
    public long sumWon() {
        return frgnWon + orgnWon;
    }
}
