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

    @Test
    void 정정은_제목_접두어로_복원된다() {
        // KIND는 정정 표시를 title 속성이 아닌 링크 텍스트의 <font>[정정]</font>에만 넣는다.
        // title 속성을 우선 쓰더라도 정정 여부가 사라지지 않도록 "[정정]" 접두어를 복원해야 한다.
        String html = "<table class='list'><tbody>"
                + "<tr><td class='first txc'>14:53</td>"
                + "<td><img class='legend' alt='코스닥'> <a href='#' onclick=\"x\" title='DXVX'>DXVX</a></td>"
                + "<td><a href='#viewer' onclick=\"openDisclsViewer('20260619000578','')\""
                + " title='단일판매ㆍ공급계약체결'><font color='#FF8040'>[정정]</font>단일판매ㆍ공급계약체결</a></td>"
                + "<td>디엑스앤브이엑스</td></tr>"
                + "</tbody></table>";

        KindDisclosure d = KindClient.parse(html).get(0);
        assertEquals("[정정]단일판매ㆍ공급계약체결", d.title());
        assertTrue(com.example.dart.filter.NewsFilter.isCorrection(d.title()));
    }

    private static String loadSample() throws IOException {
        try (InputStream in = KindClientTest.class.getResourceAsStream("/kind_today_sample.html")) {
            if (in == null) throw new IOException("kind_today_sample.html 리소스를 찾을 수 없습니다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
