package com.example.dart.kis.domain;

/**
 * KIS 등락률순위(ranking/fluctuation) 응답 한 행 — 급등 판단에 필요한 값만 추린다.
 *
 * @param code      종목코드 (stck_shrn_iscd)
 * @param name      종목명 (hts_kor_isnm)
 * @param price     현재가 원 (stck_prpr)
 * @param changePct 전일 대비 등락률 % (prdy_ctrt) — 음수면 하락
 * @param acmlVol   당일 누적 거래량 (acml_vol)
 */
public record VolumeRankItem(
        String code,
        String name,
        long price,
        double changePct,
        long acmlVol
) {}
