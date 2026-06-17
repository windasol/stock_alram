package com.example.dart.parse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void xml_엔트리_본문을_추출한다() {
        byte[] zip = zip(entry("00123.xml", "<DOCUMENT>계약금액: 2,340억원</DOCUMENT>"));
        String text = parser.toPlainText(zip);
        assertTrue(text.contains("계약금액: 2,340억원"));
    }

    @Test
    void 대문자_확장자와_htm도_인식한다() {
        assertTrue(parser.toPlainText(zip(entry("DOC.XML", "<p>대문자</p>"))).contains("대문자"));
        assertTrue(parser.toPlainText(zip(entry("body.HTM", "<p>htm 본문</p>"))).contains("htm 본문"));
    }

    @Test
    void 텍스트_확장자가_없으면_최대_엔트리로_폴백한다() {
        byte[] zip = zip(
                entry("thumb.png", "PNG_BYTES_IGNORED"),
                entry("manifest", "x"),                         // 작은 확장자 없는 엔트리
                entry("00999", "<p>본문 내용이 가장 길다 — 폴백 대상</p>")  // 큰 확장자 없는 엔트리
        );
        String text = parser.toPlainText(zip);
        assertTrue(text.contains("본문 내용이 가장 길다"));
    }

    @Test
    void 이미지만_있으면_예외를_던진다() {
        byte[] zip = zip(entry("a.jpg", "JPG"), entry("b.png", "PNG"));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> parser.toPlainText(zip));
        assertTrue(ex.getMessage().contains("본문 파일을 찾을 수 없음"));
        // 진단을 위해 엔트리 이름이 메시지에 포함된다.
        assertTrue(ex.getMessage().contains("a.jpg"));
    }

    @Test
    void ZIP이_아닌_바이트는_본문_없음으로_처리한다() {
        // PK 시그니처가 아니면 ZipInputStream은 빈 아카이브로 취급 → 본문 후보 0개.
        // (looksLikeZip 경고가 먼저 로그에 남는다.)
        byte[] notZip = "<html>error page</html>".getBytes(StandardCharsets.UTF_8);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> parser.toPlainText(notZip));
        assertTrue(ex.getMessage().contains("본문 파일을 찾을 수 없음"));
    }

    @Test
    void KIND본문_HTML에서_계약정보를_추출한다() {
        // KIND 뷰어 본문(.htm)은 ZIP이 아니라 평문 HTML — htmlToPlainText로 바로 처리한다.
        // 라벨 표기·실제 값은 우진 단일판매ㆍ공급계약체결(2026.06.17) 본문에서 가져왔다.
        String html = "<html><body><table>"
                + "<tr><td>계약금액(원)</td><td>10,932,184,000</td></tr>"
                + "<tr><td>최근매출액(원)</td><td>150,379,282,702</td></tr>"
                + "<tr><td>매출액대비(%)</td><td>7.27</td></tr>"
                + "</table></body></html>";
        DocumentParser.ContractInfo c =
                parser.extractContractFromText(parser.htmlToPlainText(html.getBytes(StandardCharsets.UTF_8)));
        assertEquals(10_932_184_000L, c.contractWon().getAsLong());
        assertEquals(150_379_282_702L, c.recentRevenueWon().getAsLong());
        assertEquals(7.27, c.salesRatioPct(), 0.001);
    }

    @Test
    void htmlToPlainText는_EUC_KR도_디코딩한다() {
        // KIND 본문은 보통 EUC-KR — UTF-8 디코딩 시 치환문자가 나오면 EUC-KR로 폴백한다.
        byte[] eucKr = "<p>계약상대방 한국전력공사</p>".getBytes(java.nio.charset.Charset.forName("EUC-KR"));
        assertTrue(parser.htmlToPlainText(eucKr).contains("계약상대방 한국전력공사"));
    }

    @Test
    void 핵심_라벨을_추출한다() {
        byte[] zip = zip(entry("00123.xml",
                "<DOCUMENT>계약금액 : 2,340억원 최근매출액 : 1조 매출액대비 : 35.2%</DOCUMENT>"));
        Map<String, String> fields = parser.extractFields(zip);
        assertTrue(fields.get("계약금액").startsWith("2,340억원"));
        assertTrue(fields.containsKey("최근매출액"));
        assertTrue(fields.containsKey("매출액대비"));
    }

    // ---- 테스트 헬퍼 ----

    private static Map.Entry<String, String> entry(String name, String content) {
        return Map.entry(name, content);
    }

    @SafeVarargs
    private static byte[] zip(Map.Entry<String, String>... entries) {
        // 순서 보존을 위해 LinkedHashMap 대신 배열 순서대로 기록한다.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> e : entries) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return baos.toByteArray();
    }
}
