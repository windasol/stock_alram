package com.example.dart.kis.domain;

/**
 * KIS 거래대금순위(volume-rank, 거래금액순) 응답 한 행 — 거래대금 섹터 랭킹에 필요한 값만 추린다.
 *
 * @param code            종목코드 (mksc_shrn_iscd)
 * @param name            종목명 (hts_kor_isnm)
 * @param tradingValueWon 당일 누적 거래대금 원 (acml_tr_pbmn)
 * @param changePct       전일 대비 등락률 % (prdy_ctrt) — 음수면 하락
 */
public record TradingValueItem(
        String code,
        String name,
        long tradingValueWon,
        double changePct
) {}
