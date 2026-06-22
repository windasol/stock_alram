package com.example.dart.kis;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KisPollerServiceTest {

    // 임계: 전일 대비 등락률 10%↑
    private static final double MIN_CHG = 10.0;

    private static VolumeRankItem item(double chg) {
        return new VolumeRankItem("123456", "종목", 10_000, chg, 7_000_000);
    }

    @Test
    void 등락률_임계_이상이면_급등() {
        assertTrue(KisPollerService.isBigGainer(item(18.0), MIN_CHG));
    }

    @Test
    void 등락률_임계_미만이면_제외() {
        assertFalse(KisPollerService.isBigGainer(item(7.0), MIN_CHG));
    }

    @Test
    void 임계와_같으면_급등() {
        assertTrue(KisPollerService.isBigGainer(item(10.0), MIN_CHG));
    }

    @Test
    void 하락이면_제외() {
        assertFalse(KisPollerService.isBigGainer(item(-12.0), MIN_CHG));
    }

    @Test
    void 섹터_요약은_종목수_비율_내림차순_종목등락률_포함() {
        List<KisPollerService.Gainer> gainers = List.of(
                new KisPollerService.Gainer("에이종목", "전기전자", 12.0),
                new KisPollerService.Gainer("비종목", "전기전자", 25.0),
                new KisPollerService.Gainer("씨종목", "제약", 11.0),
                new KisPollerService.Gainer("디종목", "미분류", 30.0));

        String msg = KisPollerService.composeSectorSummary(gainers, "정규장", LocalTime.of(14, 30));

        // 헤더: 세션·시각·전체 종목 수
        assertTrue(msg.contains("정규장 14:30"), msg);
        assertTrue(msg.contains("급등 4종목 기준"), msg);
        // 1위 전기전자 2종목 = 50%, 2·3위는 25%씩
        assertTrue(msg.contains("1. 전기전자  50% (2종목)"), msg);
        // 섹터 안 종목은 등락률 내림차순 — 비종목(+25.0%)이 에이종목(+12.0%)보다 앞
        assertTrue(msg.contains("비종목 +25.0%, 에이종목 +12.0%"), msg);
        // 동률(제약·미분류 각 1종목)은 업종명 사전순 — "미분류" < "제약"
        assertTrue(msg.indexOf("미분류") < msg.indexOf("제약"), msg);
        // 종목별 등락률이 부호와 함께 표기
        assertTrue(msg.contains("디종목 +30.0%"), msg);
    }
}
