package com.example.dart.kis.application;

import com.example.dart.common.text.TextTable;
import com.example.dart.kis.domain.FlowPhase;
import com.example.dart.kis.domain.Gainer;
import com.example.dart.kis.domain.Investor;
import com.example.dart.kis.domain.InvestorFlowItem;
import com.example.dart.kis.domain.KisMoney;
import com.example.dart.kis.domain.Session;
import com.example.dart.kis.domain.InvestorPairItem;
import com.example.dart.kis.domain.MarketInvestorFlow;
import com.example.dart.kis.domain.Turnover;
import com.example.dart.kis.domain.VolumeRankItem;
import com.example.dart.news.NewsArticle;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(GainerScout.isBigGainer(item(18.0), MIN_CHG));
    }

    @Test
    void 등락률_임계_미만이면_제외() {
        assertFalse(GainerScout.isBigGainer(item(7.0), MIN_CHG));
    }

    @Test
    void 임계와_같으면_급등() {
        assertTrue(GainerScout.isBigGainer(item(10.0), MIN_CHG));
    }

    @Test
    void 하락이면_제외() {
        assertFalse(GainerScout.isBigGainer(item(-12.0), MIN_CHG));
    }

    @Test
    void 장시작전이면_세션없음() {
        assertNull(Session.at(LocalTime.of(8, 59)));
    }

    @Test
    void 정규장_시간이면_J() {
        assertEquals(Session.REGULAR, Session.at(LocalTime.of(9, 0)));
        assertEquals(Session.REGULAR, Session.at(LocalTime.of(15, 39)));
        assertEquals("J", Session.at(LocalTime.of(10, 0)).marketDiv);
    }

    @Test
    void 마감15시40분부터_NXT_애프터마켓_NX() {
        assertEquals(Session.NXT_AFTER, Session.at(LocalTime.of(15, 40)));
        assertEquals(Session.NXT_AFTER, Session.at(LocalTime.of(20, 0)));
        assertEquals("NX", Session.at(LocalTime.of(18, 0)).marketDiv);
    }

    @Test
    void 애프터마켓_종료후면_세션없음() {
        assertNull(Session.at(LocalTime.of(20, 1)));
    }

    @Test
    void 섹터_요약은_종목수_비율_내림차순_종목등락률_포함() {
        List<Gainer> gainers = List.of(
                new Gainer("에이종목", "전기전자", 12.0),
                new Gainer("비종목", "전기전자", 25.0),
                new Gainer("씨종목", "제약", 11.0),
                new Gainer("디종목", "미분류", 30.0));

        String msg = SectorSummaryService.compose(gainers, "정규장", LocalTime.of(14, 30));

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
        List<Turnover> items = List.of(
                new Turnover("삼성전자", "반도체", 1_800_000_000_000L),     // 1.8조
                new Turnover("SK하이닉스", "반도체", 1_200_000_000_000L),   // 1.2조 → 반도체 합 3.0조
                new Turnover("현대차", "자동차", 700_000_000_000L));        // 0.7조

        String msg = TurnoverRankingService.compose(items, "정규장", LocalTime.of(14, 30));

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
        assertEquals("4.2조", KisMoney.formatWon(4_200_000_000_000L));
        assertEquals("380억", KisMoney.formatWon(38_000_000_000L));
    }

    @Test
    void formatNetWon_부호를_붙여_표기() {
        assertEquals("+380억", KisMoney.formatNetWon(38_000_000_000L));
        assertEquals("-1,234억", KisMoney.formatNetWon(-123_400_000_000L));
        assertEquals("0", KisMoney.formatNetWon(0));
    }

    @Test
    void 수급_랭킹은_매수_매도를_같은_순위_행에_좌우로_나열() {
        List<InvestorFlowItem> buys = List.of(
                new InvestorFlowItem("005930", "삼성전자", 123_400_000_000L, 2.1),
                new InvestorFlowItem("000660", "SK하이닉스", 50_000_000_000L, 1.0));
        List<InvestorFlowItem> sells = List.of(
                new InvestorFlowItem("035720", "카카오", -98_700_000_000L, -1.5));

        String msg = InvestorFlowService.composeInvestorFlow(
                Investor.FOREIGN, buys, sells, "정규장", LocalTime.of(13, 20), "가집계·추정");

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
    void 수급_랭킹_지수라인을_넘기면_제목_아래에_코스피_헤드라인이_붙는다() {
        List<InvestorFlowItem> buys = List.of(
                new InvestorFlowItem("005930", "삼성전자", 123_400_000_000L, 2.1));
        String indexLine = "🇰🇷 코스피 2,750.32 ▲ +0.82% · 코스닥 850.10 ▼ -0.35%";

        String msg = InvestorFlowService.composeInvestorFlow(
                Investor.FOREIGN, buys, List.of(), "정규장", LocalTime.of(13, 20), "외국계 실시간", indexLine);

        assertTrue(msg.contains(indexLine), msg);
        // 제목 줄 바로 다음(코드블록 시작 전)에 지수 헤드라인이 온다
        List<String> lines = msg.lines().toList();
        assertTrue(lines.get(0).contains("외국인") && lines.get(0).contains("수급 TOP"), lines.get(0));
        assertEquals(indexLine, lines.get(1));
    }

    @Test
    void 수급_랭킹_지수라인이_null이면_헤드라인_없이_기존과_동일() {
        List<InvestorFlowItem> buys = List.of(
                new InvestorFlowItem("005930", "삼성전자", 123_400_000_000L, 2.1));

        String withNull = InvestorFlowService.composeInvestorFlow(
                Investor.FOREIGN, buys, List.of(), "정규장", LocalTime.of(13, 20), "외국계 실시간", null);
        String sixArg = InvestorFlowService.composeInvestorFlow(
                Investor.FOREIGN, buys, List.of(), "정규장", LocalTime.of(13, 20), "외국계 실시간");

        assertEquals(sixArg, withNull);
        assertFalse(withNull.contains("🇰🇷"), withNull);
    }

    @Test
    void 수급_랭킹_양쪽_다_비면_데이터없음_표기() {
        String msg = InvestorFlowService.composeInvestorFlow(
                Investor.INSTITUTION, List.of(), List.of(), "정규장", LocalTime.of(13, 20), "가집계·추정");
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

        String msg = InvestorFlowService.composeInvestorPair(dualBuy, dualSell, "정규장", LocalTime.of(13, 20), "가집계·추정");

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
        String msg = InvestorFlowService.composeInvestorPair(dualBuy, List.of(), "정규장", LocalTime.of(13, 20), "가집계·추정");
        assertTrue(msg.contains("(해당 종목 없음)"), msg);
    }

    @Test
    void flowPhase는_시각에_따라_추정_KRX확정_NXT확정으로_갈린다() {
        // 09:00 이전엔 발송 없음(null)
        assertNull(FlowPhase.at(LocalTime.of(8, 59)));
        // 09:00~15:35 추정
        assertEquals(FlowPhase.ESTIMATE, FlowPhase.at(LocalTime.of(9, 0)));
        assertEquals(FlowPhase.ESTIMATE, FlowPhase.at(LocalTime.of(15, 34)));
        // 15:35~20:05 KRX 확정
        assertEquals(FlowPhase.KRX_CONFIRMED, FlowPhase.at(LocalTime.of(15, 35)));
        assertEquals(FlowPhase.KRX_CONFIRMED, FlowPhase.at(LocalTime.of(20, 4)));
        // 20:05~ NXT 최종 확정
        assertEquals(FlowPhase.NXT_CONFIRMED, FlowPhase.at(LocalTime.of(20, 5)));
        assertEquals(FlowPhase.NXT_CONFIRMED, FlowPhase.at(LocalTime.of(23, 0)));
    }

    @Test
    void 확정_시장구분은_현재시각으로_판단_20시05분이후_NX_그전_J() {
        assertEquals("J", FlowPhase.confirmedMarketDiv(LocalTime.of(15, 35)));
        assertEquals("J", FlowPhase.confirmedMarketDiv(LocalTime.of(20, 4)));
        assertEquals("NX", FlowPhase.confirmedMarketDiv(LocalTime.of(20, 5)));
        assertEquals("NX", FlowPhase.confirmedMarketDiv(LocalTime.of(23, 0)));
    }

    @Test
    void 수급_facts는_업종주석을_붙이고_미선물_거래대금을_포함() {
        List<InvestorFlowItem> frgnBuys = List.of(
                new InvestorFlowItem("005930", "삼성전자", 120_000_000_000L, 1.8),
                new InvestorFlowItem("000660", "SK하이닉스", 80_000_000_000L, 1.2));
        List<InvestorFlowItem> frgnSells = List.of(
                new InvestorFlowItem("035720", "카카오", -90_000_000_000L, -1.5));
        List<InvestorFlowItem> instBuys = List.of(
                new InvestorFlowItem("373220", "LG에너지솔루션", 50_000_000_000L, 0.9));
        List<InvestorFlowItem> instSells = List.of();
        List<InvestorPairItem> dualBuy = List.of(
                new InvestorPairItem("005930", "삼성전자", 120_000_000_000L, 30_000_000_000L, 1.8));
        Map<String, String> sectors = Map.of(
                "005930", "반도체", "000660", "반도체", "373220", "2차전지", "035720", "미분류");

        String facts = MacroReportService.buildFlowFacts(
                "🇰🇷 **국내 지수** | 코스피 -0.8%, 코스닥 +0.5%",
                "📊 시장 수급 | 코스피 외국인 -3,200억·기관 +1,500억",
                "💱 **원달러** | 1,350.2원 (+0.9%)",
                "🌎 **미국 선물** | S&P +0.4%", frgnBuys, frgnSells, instBuys, instSells,
                dualBuy, "💰 거래대금 섹터 랭킹 ...", sectors);

        // 인과 사슬 순서: 지수(맨 앞) → 시장 전체 수급 → 환율 → 미선물 → 종목별 수급
        assertTrue(facts.startsWith("🇰🇷 **국내 지수** | 코스피 -0.8%, 코스닥 +0.5%"), facts);
        assertTrue(facts.indexOf("국내 지수") < facts.indexOf("시장 수급"), facts);
        assertTrue(facts.indexOf("시장 수급") < facts.indexOf("원달러"), facts);
        assertTrue(facts.indexOf("원달러") < facts.indexOf("미국 선물"), facts);
        assertTrue(facts.indexOf("미국 선물") < facts.indexOf("[외국인]"), facts);
        // 외국인 순매수에 업종 주석 + 금액
        assertTrue(facts.contains("삼성전자(반도체) +1,200억"), facts);
        assertTrue(facts.contains("SK하이닉스(반도체) +800억"), facts);
        // 외국인 순매도
        assertTrue(facts.contains("순매도: 카카오 -900억"), facts);
        // 미분류 업종은 괄호 생략(카카오엔 업종 주석 없음)
        assertFalse(facts.contains("카카오(미분류)"), facts);
        // 기관 순매도는 비어 "(없음)"
        assertTrue(facts.contains("[기관] 순매수: LG에너지솔루션(2차전지) +500억"), facts);
        assertTrue(facts.contains("순매도: (없음)"), facts);
        // 양매수 종목(금액 없이 종목명+업종)
        assertTrue(facts.contains("[외국인+기관 양매수] 삼성전자(반도체)"), facts);
        // 거래대금 랭킹이 뒤에 붙음
        assertTrue(facts.contains("💰 거래대금 섹터 랭킹"), facts);
    }

    @Test
    void 수급_facts는_미선물_거래대금_null이면_생략하고_양매수_없으면_누락() {
        List<InvestorFlowItem> frgnBuys = List.of(
                new InvestorFlowItem("005930", "삼성전자", 120_000_000_000L, 1.8));
        Map<String, String> sectors = Map.of("005930", "반도체");

        String facts = MacroReportService.buildFlowFacts(
                null, null, null, null, frgnBuys, List.of(), List.of(), List.of(),
                List.of(), null, sectors);

        // 지수·시장수급·환율·미선물 없으면 외국인 줄로 시작
        assertTrue(facts.startsWith("[외국인] 순매수: 삼성전자(반도체) +1,200억"), facts);
        // 양매수 섹션 없음
        assertFalse(facts.contains("양매수"), facts);
        // 거래대금 랭킹 없음
        assertFalse(facts.contains("거래대금"), facts);
    }

    @Test
    void 시장_전체수급_헤드라인은_시장전체_외국인_기관을_접두어없이_보여준다() {
        // 시장 전체(빈 라벨) 한 건 — 코스피/코스닥 분리 없이 한 줄.
        List<MarketInvestorFlow> flows = List.of(
                new MarketInvestorFlow("", -320_000_000_000L, 150_000_000_000L, 170_000_000_000L));
        String msg = InvestorFlowService.composeMarketFlow(flows, LocalTime.of(13, 40), "가집계");

        assertTrue(msg.startsWith("📊 **시장 수급** | 13:40  (가집계)"), msg);
        assertTrue(msg.contains("🌍 외국인 -3,200억"), msg);
        assertTrue(msg.contains("🏛 기관 +1,500억"), msg);
        assertTrue(msg.contains("👤 개인 +1,700억"), msg);
        // 빈 시장 라벨이면 접두어("코스피"/"코스닥")가 붙지 않는다.
        assertFalse(msg.contains("코스피"), msg);
        assertFalse(msg.contains("코스닥"), msg);
        // 라벨 자리 앞 공백 없이 외국인 라벨이 줄 맨 앞에 온다.
        assertTrue(msg.contains("\n🌍 외국인 -3,200억"), msg);
    }

    @Test
    void 시장수급_헤드라인은_코스피_코스닥을_각각_접두어붙여_두줄로_보여준다() {
        // 코스피·코스닥 분리 — 두 엔트리면 시장 접두어를 붙여 각각 한 줄씩.
        List<MarketInvestorFlow> flows = List.of(
                new MarketInvestorFlow("코스피", -320_000_000_000L, 150_000_000_000L, 170_000_000_000L),
                new MarketInvestorFlow("코스닥", 80_000_000_000L, -30_000_000_000L, -50_000_000_000L));
        String msg = InvestorFlowService.composeMarketFlow(flows, LocalTime.of(13, 40), "가집계");

        assertTrue(msg.contains("코스피  🌍 외국인 -3,200억 · 🏛 기관 +1,500억 · 👤 개인 +1,700억"), msg);
        assertTrue(msg.contains("코스닥  🌍 외국인 +800억 · 🏛 기관 -300억 · 👤 개인 -500억"), msg);
        // 코스피·코스닥이 서로 다른 줄에 온다(제목 제외 2줄).
        assertEquals(3, msg.lines().count(), msg);
    }

    @Test
    void 시장수급_리포트라인은_코스피_코스닥을_슬래시로_구분한다() {
        String line = InvestorFlowService.marketFlowLine(List.of(
                new MarketInvestorFlow("코스피", -320_000_000_000L, 150_000_000_000L, 170_000_000_000L),
                new MarketInvestorFlow("코스닥", 80_000_000_000L, -30_000_000_000L, -50_000_000_000L)));
        assertEquals("📊 시장 수급 | 코스피 외국인 -3,200억·기관 +1,500억·개인 +1,700억 / "
                + "코스닥 외국인 +800억·기관 -300억·개인 -500억", line);
    }

    @Test
    void 시장_전체수급_헤드라인에_지수라인을_넘기면_제목_아래에_코스피가_붙는다() {
        List<MarketInvestorFlow> flows = List.of(
                new MarketInvestorFlow("", -320_000_000_000L, 150_000_000_000L, 170_000_000_000L));
        String indexLine = "🇰🇷 코스피 2,750.32 ▲ +0.82% · 코스닥 850.10 ▼ -0.35%";

        String msg = InvestorFlowService.composeMarketFlow(flows, LocalTime.of(13, 40), "가집계", indexLine);

        // 제목(0) → 지수(1) → 수급(2) 순서
        List<String> lines = msg.lines().toList();
        assertTrue(lines.get(0).startsWith("📊 **시장 수급**"), lines.get(0));
        assertEquals(indexLine, lines.get(1));
        assertTrue(lines.get(2).contains("🌍 외국인 -3,200억"), lines.get(2));
    }

    @Test
    void 시장_전체수급_리포트라인은_컴팩트_한줄이고_비면_null() {
        assertNull(InvestorFlowService.marketFlowLine(List.of()));
        String line = InvestorFlowService.marketFlowLine(List.of(
                new MarketInvestorFlow("", -320_000_000_000L, 150_000_000_000L, 170_000_000_000L)));
        assertEquals("📊 시장 수급 | 외국인 -3,200억·기관 +1,500억·개인 +1,700억", line);
    }

    @Test
    void 뉴스_헤드라인_블록은_정규화중복을_접고_출처_제목을_시각과_함께_보여준다() {
        ZonedDateTime t = ZonedDateTime.of(2026, 7, 7, 13, 20, 0, 0, ZoneId.of("Asia/Seoul"));
        List<NewsArticle> articles = List.of(
                new NewsArticle("한국경제", "[속보] A사, 5조원 수주", "l1", null, "d", t),
                new NewsArticle("연합뉴스", "A사 5조원 수주", "l2", null, "d", t.plusMinutes(1)),  // 정규화하면 중복
                new NewsArticle("이데일리", "B사 신약 FDA 승인", "l3", null, "d", null));           // 발행시각 없음

        String block = MacroReportService.buildNewsHeadlines(articles, 60);

        assertNotNull(block);
        assertTrue(block.startsWith("[지난 1시간 주요 뉴스 헤드라인]"), block);
        // 첫 헤드라인(한국경제)만 남고 정규화 중복(연합뉴스)은 접힌다
        assertTrue(block.contains("13:20 한국경제 | [속보] A사, 5조원 수주"), block);
        assertFalse(block.contains("연합뉴스"), block);
        // 발행시각을 모르면 시각 없이 "출처 | 제목"
        assertTrue(block.contains("이데일리 | B사 신약 FDA 승인"), block);
    }

    @Test
    void 뉴스_헤드라인_블록은_비면_null() {
        assertNull(MacroReportService.buildNewsHeadlines(List.of(), 60));
        assertNull(MacroReportService.buildNewsHeadlines(null, 60));
    }

    @Test
    void padDisplay_한글은_표시폭2로_정렬() {
        // "삼성전자"=표시폭8 → 폭12면 공백4 좌측정렬
        assertEquals("삼성전자    ", TextTable.padDisplay("삼성전자", 12, true));
        // 우측정렬(금액)
        assertEquals("  +1,234억", TextTable.padDisplay("+1,234억", 10, false));
        // 폭 초과 시 표시폭 기준으로 자른다(전각이 폭을 안 넘게)
        assertEquals("삼성", TextTable.padDisplay("삼성전자", 4, true));
    }
}
