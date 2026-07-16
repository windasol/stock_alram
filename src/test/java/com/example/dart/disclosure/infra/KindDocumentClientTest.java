package com.example.dart.disclosure.infra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KIND 뷰어 응답 파싱 검증 — 스니펫은 실제 우진 공시(acptno=20260617000388) 뷰어/콘텐츠 응답에서 가져왔다.
 */
class KindDocumentClientTest {

    @Test
    void mainDoc_옵션에서_docNo를_뽑는다() {
        String viewer = "<select id=\"mainDoc\" name=\"mainDoc\" title=\"본문\">"
                + "<option value=\"\">본문선택</option>"
                + "<option value='20260617000606|Y'selected=\"selected\">단일판매ㆍ공급계약체결 (2026.06.17)</option>"
                + "</select>";
        assertEquals("20260617000606", KindDocumentClient.extractDocNo(viewer));
    }

    @Test
    void 제목줄에서_종목코드를_뽑는다() {
        assertEquals("105840", KindDocumentClient.extractStockCode("<h1 class=\"ttl\">우진 (105840)</h1>"));
    }

    @Test
    void 종목코드가_없으면_null() {
        // 코넥스·비상장 등 코드가 없는 경우.
        assertNull(KindDocumentClient.extractStockCode("<h1 class=\"ttl\">비상장회사</h1>"));
    }

    @Test
    void setPath에서_본문URL을_뽑는다() {
        String contents = "parent.setPath('','https://kind.krx.co.kr/external/2026/06/17/000388/20260617000606/91370.htm',"
                + "'/external/2026/06/17/000388/20260617000606/91370','01','30');";
        assertEquals("https://kind.krx.co.kr/external/2026/06/17/000388/20260617000606/91370.htm",
                KindDocumentClient.extractDocUrl(contents));
    }
}
