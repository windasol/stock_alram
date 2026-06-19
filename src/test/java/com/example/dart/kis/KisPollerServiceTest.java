package com.example.dart.kis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KisPollerServiceTest {

    // 임계: 등락률 8%↑, RVOL 4배↑, 거래대금 50억↑
    private static final double MIN_CHG = 8.0;
    private static final double MIN_RVOL = 4.0;
    private static final long MIN_AMT = 5_000_000_000L;

    private static VolumeRankItem item(double chg, long acml, long avrg, long amt) {
        return new VolumeRankItem("123456", "종목", 10_000, chg, acml, avrg, amt);
    }

    @Test
    void 세_조건_모두_충족해야_급등() {
        // 등락률 18% · RVOL 7배 · 거래대금 320억 → 통과
        assertTrue(KisPollerService.isVolatilitySpike(
                item(18.0, 7_000_000, 1_000_000, 32_000_000_000L), MIN_CHG, MIN_RVOL, MIN_AMT));
    }

    @Test
    void 거래량은_터졌지만_등락률_미달이면_제외() {
        // RVOL 10배지만 등락률 3% → 제외 (가격 안 움직인 일시적 거래량)
        assertFalse(KisPollerService.isVolatilitySpike(
                item(3.0, 10_000_000, 1_000_000, 32_000_000_000L), MIN_CHG, MIN_RVOL, MIN_AMT));
    }

    @Test
    void 등락률_높아도_RVOL_미달이면_제외() {
        // 등락률 20%지만 평소의 1.5배뿐 → 제외 (대형주 상시 거래 등)
        assertFalse(KisPollerService.isVolatilitySpike(
                item(20.0, 1_500_000, 1_000_000, 32_000_000_000L), MIN_CHG, MIN_RVOL, MIN_AMT));
    }

    @Test
    void 거래대금_하한_미달이면_제외() {
        // 등락률·RVOL 충족하지만 거래대금 10억 → 제외 (잡주 노이즈)
        assertFalse(KisPollerService.isVolatilitySpike(
                item(18.0, 7_000_000, 1_000_000, 1_000_000_000L), MIN_CHG, MIN_RVOL, MIN_AMT));
    }
}
