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

    @Test
    void formatNetWon_부호를_붙여_표기() {
        assertEquals("+380억", KisPollerService.formatNetWon(38_000_000_000L));
        assertEquals("-1,234억", KisPollerService.formatNetWon(-123_400_000_000L));
        assertEquals("0", KisPollerService.formatNetWon(0));
    }

    @Test
    void 수급_랭킹은_매수_매도를_같은_순위_행에_좌우로_나열() {
        List<InvestorFlowItem> buys = List.of(
                new InvestorFlowItem("005930", "삼성전자", 123_400_000_000L, 2.1),
                new InvestorFlowItem("000660", "SK하이닉스", 50_000_000_000L, 1.0));
        List<InvestorFlowItem> sells = List.of(
                new InvestorFlowItem("035720", "카카오", -98_700_000_000L, -1.5));

        String msg = KisPollerService.composeInvestorFlow(
                KisClient.Investor.FOREIGN, buys, sells, "정규장", LocalTime.of(13, 20), "가집계·추정");

        assertTrue(msg.contains("외국인"), msg);
        assertTrue(msg.contains("정규장 13:20"), msg);
        assertTrue(msg.contains("가집계"), msg);
        // 코드블록 + 좌우 2열 헤더
        assertTrue(msg.contains("```"), msg);
        assertTrue(msg.contains("매수(많이산)"), msg);
        assertTrue(msg.contains("매도(많이판)"), msg);
        // 1위 행에 매수(삼성전자 +1,234억)와 매도(카카오 -987억)가 같이 온다
        String firstRow = msg.lines().filter(l -> l.trim().startsWith("1 ")).findFirst().orElse("");
        assertTrue(firstRow.contains("삼성전자") && firstRow.contains("+1,234억"), firstRow);
        assertTrue(firstRow.contains("카카오") && firstRow.contains("-987억"), firstRow);
        // 한 행 안에서 매수가 매도보다 왼쪽
        assertTrue(firstRow.indexOf("삼성전자") < firstRow.indexOf("카카오"), firstRow);
        // 매도가 더 짧은 2위 행: 매수(SK하이닉스)만 있고 매도 칸은 비어 카카오 재등장 없음
        String secondRow = msg.lines().filter(l -> l.trim().startsWith("2 ")).findFirst().orElse("");
        assertTrue(secondRow.contains("SK하이닉스"), secondRow);
    }

    @Test
    void 수급_랭킹_양쪽_다_비면_데이터없음_표기() {
        String msg = KisPollerService.composeInvestorFlow(
                KisClient.Investor.INSTITUTION, List.of(), List.of(), "정규장", LocalTime.of(13, 20), "가집계·추정");
        assertTrue(msg.contains("기관"), msg);
        assertTrue(msg.contains("(데이터 없음)"), msg);
    }

    @Test
    void 동시매매는_양매수_양매도_종목을_외국인_기관_금액과_함께_나열() {
        List<InvestorPairItem> dualBuy = List.of(
                new InvestorPairItem("005930", "삼성전자", 120_000_000_000L, 80_000_000_000L, 1.8),
                new InvestorPairItem("000660", "SK하이닉스", 50_000_000_000L, 30_000_000_000L, 1.0));
        List<InvestorPairItem> dualSell = List.of(
                new InvestorPairItem("035720", "카카오", -30_000_000_000L, -15_000_000_000L, -1.5));

        String msg = KisPollerService.composeInvestorPair(dualBuy, dualSell, "정규장", LocalTime.of(13, 20), "가집계·추정");

        assertTrue(msg.contains("외국인+기관 동시매매"), msg);
        assertTrue(msg.contains("정규장 13:20"), msg);
        assertTrue(msg.contains("양매수"), msg);
        assertTrue(msg.contains("양매도"), msg);
        // 양매수 1위 행에 외국인·기관 금액이 함께
        String buyRow = msg.lines().filter(l -> l.trim().startsWith("1 ") && l.contains("삼성전자"))
                .findFirst().orElse("");
        assertTrue(buyRow.contains("삼성전자") && buyRow.contains("+1,200억") && buyRow.contains("+800억"), buyRow);
        // 양매도 행은 둘 다 음수
        String sellRow = msg.lines().filter(l -> l.contains("카카오")).findFirst().orElse("");
        assertTrue(sellRow.contains("-300억") && sellRow.contains("-150억"), sellRow);
        // 양매수 섹션이 양매도 섹션보다 앞
        assertTrue(msg.indexOf("양매수") < msg.indexOf("양매도"), msg);
    }

    @Test
    void 동시매매_한쪽이_비면_해당_종목_없음_표기() {
        List<InvestorPairItem> dualBuy = List.of(
                new InvestorPairItem("005930", "삼성전자", 120_000_000_000L, 80_000_000_000L, 1.8));
        String msg = KisPollerService.composeInvestorPair(dualBuy, List.of(), "정규장", LocalTime.of(13, 20), "가집계·추정");
        assertTrue(msg.contains("(해당 종목 없음)"), msg);
    }

    @Test
    void padDisplay_한글은_표시폭2로_정렬() {
        // "삼성전자"=표시폭8 → 폭12면 공백4 좌측정렬
        assertEquals("삼성전자    ", KisPollerService.padDisplay("삼성전자", 12, true));
        // 우측정렬(금액)
        assertEquals("  +1,234억", KisPollerService.padDisplay("+1,234억", 10, false));
        // 폭 초과 시 표시폭 기준으로 자른다(전각이 폭을 안 넘게)
        assertEquals("삼성", KisPollerService.padDisplay("삼성전자", 4, true));
    }
}
