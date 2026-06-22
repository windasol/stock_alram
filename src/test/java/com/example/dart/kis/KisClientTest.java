package com.example.dart.kis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KisClientTest {

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

        List<VolumeRankItem> items = KisClient.parseFluctuationRank(json);
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
        assertTrue(KisClient.parseFluctuationRank(json).isEmpty());
    }

    @Test
    void 현재가_응답에서_업종명을_파싱한다() {
        String json = """
                {
                  "rt_cd": "0", "msg1": "정상처리",
                  "output": { "stck_prpr": "12,500", "bstp_kor_isnm": "반도체와반도체장비" }
                }
                """;
        assertEquals("반도체와반도체장비", KisClient.parseSector(json));
    }

    @Test
    void 업종_조회_비정상이면_빈_문자열() {
        assertEquals("", KisClient.parseSector("{\"rt_cd\":\"1\",\"msg1\":\"오류\"}"));
    }
}
