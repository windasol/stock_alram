package com.example.dart.kind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-06-12 KIND 오늘의공시 실응답 캡처(kind_today_sample.html) 기반 파싱 검증. */
class KindClientTest {

    @Test
    void 실응답_캡처를_파싱한다() throws IOException {
        List<KindDisclosure> list = KindClient.parse(loadSample());

        assertFalse(list.isEmpty());

        KindDisclosure first = list.get(0);
        assertEquals("14:10", first.time());
        assertEquals("유가증권", first.market());
        assertEquals("Y", first.marketCode());
        assertEquals("하나금융지주", first.company());
        assertEquals("임원ㆍ주요주주특정증권등소유상황보고서", first.title());
        assertEquals("20260612000528", first.acptNo());
        assertEquals("이재술", first.submitter());
        assertEquals("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno=20260612000528",
                first.detailUrl());

        // 모든 행이 접수번호·시각을 가져야 한다
        for (KindDisclosure d : list) {
            assertTrue(d.acptNo().matches("\\d{14}"), "접수번호 형식: " + d.acptNo());
            assertTrue(d.time().matches("\\d{2}:\\d{2}"), "시각 형식: " + d.time());
        }
    }

    @Test
    void 공시_테이블이_없으면_차단_의심으로_실패한다() {
        assertThrows(IllegalStateException.class,
                () -> KindClient.parse("<html><body>점검 중입니다</body></html>"));
    }

    private static String loadSample() throws IOException {
        try (InputStream in = KindClientTest.class.getResourceAsStream("/kind_today_sample.html")) {
            if (in == null) throw new IOException("kind_today_sample.html 리소스를 찾을 수 없습니다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
