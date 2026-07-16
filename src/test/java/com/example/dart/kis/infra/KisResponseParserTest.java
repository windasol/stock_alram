package com.example.dart.kis.infra;

import com.example.dart.kis.domain.Investor;
import com.example.dart.kis.domain.InvestorConfirmed;
import com.example.dart.kis.domain.InvestorFlowItem;
import com.example.dart.kis.domain.InvestorPairItem;
import com.example.dart.kis.domain.MinuteCandle;
import com.example.dart.kis.domain.TradingValueItem;
import com.example.dart.kis.domain.VolumeRankItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KisResponseParserTest {

    @Test
    void 등락률순위_응답을_파싱한다() {
        String json = """
                {
                  "rt_cd": "0", "msg_cd": "MCA00000", "msg1": "정상처리",
                  "output": [
                    {
                      "stck_shrn_iscd": "123456",
                      "data_rank": "1",
                      "hts_kor_isnm": "테스트종목",
                      "stck_prpr": "12,500",
                      "prdy_ctrt": "18.5",
                      "acml_vol": "7200000"
                    }
                  ]
                }
                """;

        List<VolumeRankItem> items = KisResponseParser.parseFluctuationRank(json);
        assertEquals(1, items.size());
        VolumeRankItem it = items.get(0);
        assertEquals("123456", it.code());
        assertEquals("테스트종목", it.name());
        assertEquals(12_500L, it.price());
        assertEquals(18.5, it.changePct());
        assertEquals(7_200_000L, it.acmlVol());
    }

    @Test
    void 비정상_응답이면_빈_목록() {
        String json = "{\"rt_cd\":\"1\",\"msg1\":\"오류\",\"output\":[]}";
        assertTrue(KisResponseParser.parseFluctuationRank(json).isEmpty());
    }

    @Test
    void 현재가_응답에서_업종명을_파싱한다() {
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": { "stck_prpr": "12,500", "bstp_kor_isnm": "반도체와반도체장비" }
                }
                """;
        assertEquals("반도체와반도체장비", KisResponseParser.parseSector(json));
    }

    @Test
    void 업종_조회_비정상이면_빈_문자열() {
        assertEquals("", KisResponseParser.parseSector("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}"));
    }

    @Test
    void 호가_응답에서_매도1_매수1_중간값을_계산한다() {
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output1": { "askp1": "10,200", "bidp1": "10,000" }
                }
                """;
        assertEquals(10_100L, KisResponseParser.parseAskingMid(json).getAsLong());
    }

    @Test
    void 한쪽_호가가_0이면_empty() {
        // 호가창이 비어있거나(장 닫힘) 미상장 — 중간값 의미 없음.
        assertTrue(KisResponseParser.parseAskingMid(
                "{\"rt_cd\":\"0\",\"output1\":{\"askp1\":\"10,200\",\"bidp1\":\"0\"}}").isEmpty());
        assertTrue(KisResponseParser.parseAskingMid(
                "{\"rt_cd\":\"0\",\"output1\":{\"askp1\":\"\",\"bidp1\":\"\"}}").isEmpty());
    }

    @Test
    void 호가_비정상_응답이면_empty() {
        assertTrue(KisResponseParser.parseAskingMid("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}").isEmpty());
        assertTrue(KisResponseParser.parseAskingMid("not json").isEmpty());
    }

    @Test
    void 거래대금순위_응답을_파싱한다() {
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "mksc_shrn_iscd": "005930", "hts_kor_isnm": "삼성전자",
                      "acml_tr_pbmn": "1,800,000,000,000", "prdy_ctrt": "2.5" }
                  ]
                }
                """;
        List<TradingValueItem> items = KisResponseParser.parseVolumeRank(json);
        assertEquals(1, items.size());
        TradingValueItem it = items.get(0);
        assertEquals("005930", it.code());
        assertEquals("삼성전자", it.name());
        assertEquals(1_800_000_000_000L, it.tradingValueWon());
        assertEquals(2.5, it.changePct());
    }

    @Test
    void 거래대금순위_비정상이면_빈목록() {
        assertTrue(KisResponseParser.parseVolumeRank("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}").isEmpty());
        assertTrue(KisResponseParser.parseVolumeRank("not json").isEmpty());
    }

    @Test
    void 외국인_수급_응답을_파싱하고_백만원을_원으로_환산한다() {
        // 외국인은 frgn_ntby_tr_pbmn 을 읽고, 단위가 백만원이라 ×1,000,000 으로 원 환산한다(orgn 필드는 무시).
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "mksc_shrn_iscd": "005930", "hts_kor_isnm": "삼성전자",
                      "prdy_ctrt": "2.1",
                      "frgn_ntby_tr_pbmn": "360,000",
                      "orgn_ntby_tr_pbmn": "-5,000" }
                  ]
                }
                """;
        List<InvestorFlowItem> items = KisResponseParser.parseInvestorFlow(json, Investor.FOREIGN);
        assertEquals(1, items.size());
        InvestorFlowItem it = items.get(0);
        assertEquals("005930", it.code());
        assertEquals("삼성전자", it.name());
        assertEquals(360_000L * 1_000_000L, it.netValueWon());   // 3,600억
        assertEquals(2.1, it.changePct());
    }

    @Test
    void 기관_수급은_orgn필드를_읽고_순매도는_음수다() {
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "mksc_shrn_iscd": "000660", "hts_kor_isnm": "SK하이닉스",
                      "prdy_ctrt": "-1.5",
                      "frgn_ntby_tr_pbmn": "1,000",
                      "orgn_ntby_tr_pbmn": "-98,700" }
                  ]
                }
                """;
        List<InvestorFlowItem> items = KisResponseParser.parseInvestorFlow(json, Investor.INSTITUTION);
        assertEquals(1, items.size());
        assertEquals(-98_700L * 1_000_000L, items.get(0).netValueWon());   // -987억
        assertEquals(-1.5, items.get(0).changePct());
    }

    @Test
    void 외국인_기관_수급_비정상이면_빈목록() {
        assertTrue(KisResponseParser.parseInvestorFlow(
                "{\"rt_cd\":\"1\",\"msg1\":\"오류\"}", Investor.FOREIGN).isEmpty());
        assertTrue(KisResponseParser.parseInvestorFlow("not json", Investor.FOREIGN).isEmpty());
    }

    @Test
    void 동시매매_응답에서_외국인_기관_거래대금을_함께_파싱한다() {
        // 한 행에 frgn·orgn 순매수대금이 함께 — 둘 다 백만원→원 환산.
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "mksc_shrn_iscd": "005930", "hts_kor_isnm": "삼성전자",
                      "prdy_ctrt": "1.8",
                      "frgn_ntby_tr_pbmn": "120,000",
                      "orgn_ntby_tr_pbmn": "80,000" }
                  ]
                }
                """;
        List<InvestorPairItem> items = KisResponseParser.parseInvestorPair(json);
        assertEquals(1, items.size());
        InvestorPairItem it = items.get(0);
        assertEquals("삼성전자", it.name());
        assertEquals(120_000L * 1_000_000L, it.frgnWon());   // 1,200억
        assertEquals(80_000L * 1_000_000L, it.orgnWon());    // 800억
        assertEquals(200_000L * 1_000_000L, it.sumWon());    // 합계 2,000억
    }

    @Test
    void 동시매매_비정상이면_빈목록() {
        assertTrue(KisResponseParser.parseInvestorPair("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}").isEmpty());
        assertTrue(KisResponseParser.parseInvestorPair("not json").isEmpty());
    }

    @Test
    void 종목별_확정수급은_당일행_외국인_기관을_백만원에서_원으로_환산한다() {
        // inquire-investor 는 output 이 일자별 배열(최신=맨앞). 당일 행에서 외국인·기관 거래대금을 읽고 ×1,000,000.
        // NAVER 사례: 기관 순매도(-2,304 백만원=-23억), 외국인 순매수(+38,931 백만원=+389억).
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "stck_bsop_date": "20260625",
                      "frgn_ntby_tr_pbmn": "38,931", "orgn_ntby_tr_pbmn": "-2,304" },
                    { "stck_bsop_date": "20260624",
                      "frgn_ntby_tr_pbmn": "100", "orgn_ntby_tr_pbmn": "200" }
                  ]
                }
                """;
        InvestorConfirmed c = KisResponseParser.parseInvestorConfirmed(json);
        assertEquals("20260625", c.date());
        assertEquals(38_931L * 1_000_000L, c.foreignWon());      // +389억
        assertEquals(-2_304L * 1_000_000L, c.institutionWon());  // -23억
        assertEquals(-2_304L * 1_000_000L, c.netWon(Investor.INSTITUTION));
        assertEquals(38_931L * 1_000_000L, c.netWon(Investor.FOREIGN));
    }

    @Test
    void 외국계_실시간_응답은_순매수수량X현재가로_원을_근사한다() {
        // 외국계 매매종목 가집계 — 금액이 아니라 수량. 순매수수량=총매수-총매도, 금액(원)≈순매수수량×현재가.
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "stck_shrn_iscd": "005930", "hts_kor_isnm": "삼성전자",
                      "stck_prpr": "70,000", "prdy_ctrt": "2.1",
                      "glob_total_shnu_qty": "100,000", "glob_total_seln_qty": "30,000" }
                  ]
                }
                """;
        List<InvestorFlowItem> items = KisResponseParser.parseForeignMemberEstimate(json);
        assertEquals(1, items.size());
        InvestorFlowItem it = items.get(0);
        assertEquals("005930", it.code());
        assertEquals("삼성전자", it.name());
        // 순매수수량 (100,000 - 30,000) × 70,000원 = 49억
        assertEquals(70_000L * 70_000L, it.netValueWon());
        assertEquals(2.1, it.changePct());
    }

    @Test
    void 외국계_실시간_순매도우위면_음수() {
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": [
                    { "stck_shrn_iscd": "000660", "hts_kor_isnm": "SK하이닉스",
                      "stck_prpr": "180,000", "prdy_ctrt": "-1.2",
                      "glob_total_shnu_qty": "10,000", "glob_total_seln_qty": "25,000" }
                  ]
                }
                """;
        List<InvestorFlowItem> items = KisResponseParser.parseForeignMemberEstimate(json);
        assertEquals(-15_000L * 180_000L, items.get(0).netValueWon());   // 순매도 우위 → 음수
    }

    @Test
    void 외국계_실시간_비정상이면_빈목록() {
        assertTrue(KisResponseParser.parseForeignMemberEstimate("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}").isEmpty());
        assertTrue(KisResponseParser.parseForeignMemberEstimate("not json").isEmpty());
    }

    @Test
    void 종목별_확정수급_비정상이거나_빈output이면_null() {
        assertNull(KisResponseParser.parseInvestorConfirmed("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}"));
        assertNull(KisResponseParser.parseInvestorConfirmed("not json"));
        assertNull(KisResponseParser.parseInvestorConfirmed("{\"rt_cd\":\"0\",\"output\":[]}"));
    }

}
