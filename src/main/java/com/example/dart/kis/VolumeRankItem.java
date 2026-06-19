package com.example.dart.kis;

/**
 * KIS 거래량순위(volume-rank) 응답 한 행 — 변동성 급등 판단에 필요한 값만 추린다.
 *
 * @param code           종목코드 (mksc_shrn_iscd)
 * @param name           종목명 (hts_kor_isnm)
 * @param price          현재가 원 (stck_prpr)
 * @param changePct      전일 대비 등락률 % (prdy_ctrt) — 음수면 하락
 * @param acmlVol        당일 누적 거래량 (acml_vol)
 * @param avrgVol        평균 거래량 (avrg_vol) — RVOL 분모
 * @param tradeAmountWon 당일 누적 거래대금 원 (acml_tr_pbmn)
 */
public record VolumeRankItem(
        String code,
        String name,
        long price,
        double changePct,
        long acmlVol,
        long avrgVol,
        long tradeAmountWon
) {

    /** 상대 거래량(RVOL) = 누적거래량 ÷ 평균거래량. "평소 대비 몇 배" — 대형주는 항상 많아도 배수로는 안 튄다. */
    public double rvol() {
        return avrgVol > 0 ? (double) acmlVol / avrgVol : 0.0;
    }
}
