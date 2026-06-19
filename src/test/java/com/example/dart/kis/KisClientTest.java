package com.example.dart.kis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KisClientTest {

    @Test
    void 거래량순위_응답을_파싱한다() {
        String json = """
                {
                  "rt_cd": "0", "msg_cd": "MCA00000", "msg1": "정상처리",
                  "output": [
                    {
                      "hts_kor_isnm": "테스트종목",
                      "mksc_shrn_iscd": "123456",
                      "data_rank": "1",
                      "stck_prpr": "12,500",
                      "prdy_ctrt": "18.5",
                      "acml_vol": "7200000",
                      "prdy_vol": "900000",
                      "avrg_vol": "1000000",
                      "vol_inrt": "620.00",
                      "acml_tr_pbmn": "32000000000"
                    }
                  ]
                }
                """;

        List<VolumeRankItem> items = KisClient.parseVolumeRank(json);
        assertEquals(1, items.size());
        VolumeRankItem it = items.get(0);
        assertEquals("123456", it.code());
        assertEquals("테스트종목", it.name());
        assertEquals(12_500L, it.price());
        assertEquals(18.5, it.changePct());
        assertEquals(7_200_000L, it.acmlVol());
        assertEquals(1_000_000L, it.avrgVol());
        assertEquals(32_000_000_000L, it.tradeAmountWon());
        assertEquals(7.2, it.rvol(), 0.0001);   // 7,200,000 ÷ 1,000,000
    }

    @Test
    void 비정상_응답이면_빈_목록() {
        String json = "{\"rt_cd\":\"1\",\"msg1\":\"오류\",\"output\":[]}";
        assertTrue(KisClient.parseVolumeRank(json).isEmpty());
    }

    @Test
    void 평균거래량_0이면_RVOL_0() {
        VolumeRankItem it = new VolumeRankItem("000000", "x", 1000, 5.0, 100, 0, 1_000_000L);
        assertEquals(0.0, it.rvol());
    }
}
