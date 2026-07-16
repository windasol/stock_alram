package com.example.dart.kis.domain;

/**
 * KIS 외국인·기관 매매종목가집계(foreign-institution-total) 응답 한 행 — 장중 수급 랭킹에 필요한 값만 추린다.
 *
 * 가집계는 증권사 직원이 장중에 입력하는 순매수 추정치라 종목별 총매수/총매도 분리값은 없고 순매수만 있다.
 *
 * @param code        종목코드 (mksc_shrn_iscd)
 * @param name        종목명 (hts_kor_isnm)
 * @param netValueWon 순매수 거래대금 원 — 외국인 frgn_ntby_tr_pbmn / 기관 orgn_ntby_tr_pbmn. 음수면 순매도.
 * @param changePct   전일 대비 등락률 % (prdy_ctrt) — 음수면 하락
 */
public record InvestorFlowItem(
        String code,
        String name,
        long netValueWon,
        double changePct
) {}
