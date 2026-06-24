package com.example.dart.kis;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void 장시작전이면_세션없음() {
        assertNull(KisPollerService.sessionAt(LocalTime.of(8, 59)));
    }

    @Test
    void 정규장_시간이면_J() {
        assertEquals(KisPollerService.Session.REGULAR, KisPollerService.sessionAt(LocalTime.of(9, 0)));
        assertEquals(KisPollerService.Session.REGULAR, KisPollerService.sessionAt(LocalTime.of(15, 39)));
        assertEquals("J", KisPollerService.sessionAt(LocalTime.of(10, 0)).marketDiv);
    }

    @Test
    void 마감15시40분부터_NXT_애프터마켓_NX() {
        assertEquals(KisPollerService.Session.NXT_AFTER, KisPollerService.sessionAt(LocalTime.of(15, 40)));
        assertEquals(KisPollerService.Session.NXT_AFTER, KisPollerService.sessionAt(LocalTime.of(20, 0)));
        assertEquals("NX", KisPollerService.sessionAt(LocalTime.of(18, 0)).marketDiv);
    }

    @Test
    void 애프터마켓_종료후면_세션없음() {
        assertNull(KisPollerService.sessionAt(LocalTime.of(20, 1)));
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

    @Test
    void 거래대금_랭킹은_섹터별_거래대금합_내림차순_종목거래대금_포함() {
        List<KisPollerService.Turnover> items = List.of(
                new KisPollerService.Turnover("삼성전자", "반도체", 1_800_000_000_000L),     // 1.8조
                new KisPollerService.Turnover("SK하이닉스", "반도체", 1_200_000_000_000L),   // 1.2조 → 반도체 합 3.0조
                new KisPollerService.Turnover("현대차", "자동차", 700_000_000_000L));        // 0.7조

        String msg = KisPollerService.composeTurnoverRanking(items, "정규장", LocalTime.of(14, 30));

        assertTrue(msg.contains("거래대금 섹터 랭킹"), msg);
        assertTrue(msg.contains("정규장 14:30"), msg);
        // 1위 반도체(합 3.0조)가 자동차(0.7조)보다 앞
        assertTrue(msg.contains("1. 반도체"), msg);
        assertTrue(msg.indexOf("반도체") < msg.indexOf("자동차"), msg);
        // 섹터 합·종목 거래대금이 조 단위로 표기
        assertTrue(msg.contains("3.0조"), msg);
        // 섹터 안 종목은 거래대금 내림차순 — 삼성전자(1.8조)가 SK하이닉스(1.2조)보다 먼저
        assertTrue(msg.indexOf("삼성전자") < msg.indexOf("SK하이닉스"), msg);
    }

    @Test
    void formatWon_조_억_단위로_표기() {
        assertEquals("4.2조", KisPollerService.formatWon(4_200_000_000_000L));
        assertEquals("380억", KisPollerService.formatWon(38_000_000_000L));
    }
}
