package com.example.dart.kis;

/**
 * 종목별 투자자 '확정' 순매수 거래대금(원) — inquire-investor(FHKST01010900) 당일 행에서 외국인·기관을 함께 읽는다.
 *
 * 가집계(foreign-institution-total, 추정·14:30 동결)와 달리 증권사 화면의 마감 후 확정 수급과 같다.
 * 한 번 조회로 외국인·기관 값을 모두 주므로, 수급 랭킹을 확정치로 재구성할 때 종목당 1회 호출이면 된다.
 *
 * @param date           거래일자 YYYYMMDD (stck_bsop_date) — 당일 마감 후면 그날 확정
 * @param foreignWon     외국인 순매수 거래대금 원 (frgn_ntby_tr_pbmn, 백만원→원; 음수=순매도)
 * @param institutionWon 기관계 순매수 거래대금 원 (orgn_ntby_tr_pbmn, 백만원→원; 음수=순매도)
 */
public record InvestorConfirmed(String date, long foreignWon, long institutionWon) {

    /** 해당 투자자(외국인/기관)의 확정 순매수 거래대금(원). */
    public long netWon(KisClient.Investor inv) {
        return inv == KisClient.Investor.FOREIGN ? foreignWon : institutionWon;
    }
}
