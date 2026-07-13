package com.example.dart.parse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void 자기주식취득결정_본문에서_취득금액을_추출한다() {
        // 직접취득결정 서식 — "취득예정금액(원)" 라벨과 숫자 사이에 표 칸("보통주식")이 끼어든다.
        String html = "<html><body><table>"
                + "<tr><td>취득예정금액(원)</td><td>보통주식</td><td>30,000,000,000</td></tr>"
                + "</table></body></html>";
        String text = parser.htmlToPlainText(html.getBytes(StandardCharsets.UTF_8));
        assertEquals(30_000_000_000L, parser.acquisitionAmountWon(text).getAsLong());
    }

    @Test
    void 취득금액_라벨_변형과_미기재를_처리한다() {
        // "취득금액(원)" 표기도 인식.
        assertEquals(5_000_000_000L,
                parser.acquisitionAmountWon("취득금액(원) 5,000,000,000").getAsLong());
        // 값이 "-"(미기재)면 추출 없음.
        assertTrue(parser.acquisitionAmountWon("취득예정금액(원) 보통주식 - 기타주식 -").isEmpty());
    }

    @Test
    void 신탁계약체결_본문의_계약금액을_추출한다() {
        // 자기주식취득 신탁계약 체결 결정 서식 — 금액 라벨이 "계약금액(원)"(신탁계약금액).
        // 직접취득결정과 서식이 달라 composeTreasuryTrust가 이 계약금액 파서를 재사용한다.
        String html = "<html><body><table>"
                + "<tr><td>계약금액(원)</td><td>10,000,000,000</td></tr>"
                + "<tr><td>계약기간</td><td>시작일 2026-07-14 종료일 2027-01-13</td></tr>"
                + "</table></body></html>";
        String text = parser.htmlToPlainText(html.getBytes(StandardCharsets.UTF_8));
        assertEquals(10_000_000_000L, parser.extractContractFromText(text).contractWon().getAsLong());
    }

    @Test
    void htmlToPlainText는_EUC_KR도_디코딩한다() {
        // KIND 본문은 보통 EUC-KR — UTF-8 디코딩 시 치환문자가 나오면 EUC-KR로 폴백한다.
        byte[] eucKr = "<p>계약상대방 한국전력공사</p>".getBytes(java.nio.charset.Charset.forName("EUC-KR"));
        assertTrue(parser.htmlToPlainText(eucKr).contains("계약상대방 한국전력공사"));
    }

    @Test
    void 계약상대방_라벨_두_형태와_비공개를_처리한다() {
        // 실제 공시 평문 형식 — 라벨이 "계약상대방"(에너토크)·"계약상대"(대한전선·한화오션) 둘 다 쓰인다.
        assertEquals("WAA EUROPE", parser.extractContractFromText(
                "3. 계약상대방 WAA EUROPE - 최근 매출액(원) - 주요사업 무역").counterparty());
        assertEquals("한국전력공사", parser.extractContractFromText(
                "3. 계약상대 한국전력공사 - 회사와의 관계 - 4. 판매ㆍ공급지역 대한민국").counterparty());
        // 공백 포함 다단어 상대방도 통째로 추출한다.
        assertEquals("아시아 지역 선주", parser.extractContractFromText(
                "3. 계약상대 아시아 지역 선주 - 회사와의 관계 - 4. 판매ㆍ공급지역 아시아").counterparty());
        // 비공개("-")는 추출하지 않는다(미기재 정상).
        assertNull(parser.extractContractFromText(
                "3. 계약상대방 - 회사와의 관계 - 4. 판매ㆍ공급지역").counterparty());
    }

    @Test
    void 명시비율이_있으면_그대로_쓴다() {
        // 공시가 매출액대비 30%로 적었으면(거래소 표준 지표) 연환산 없이 그대로 표시 — 다년 계약이어도 변형 안 함.
        assertEquals("매출 대비 30.0%",
                DocumentParser.salesRatioLabel(1L, java.util.OptionalLong.empty(), 30.0));
    }

    @Test
    void 명시비율_없으면_계약금액_매출_총비율() {
        // 명시값이 없을 때만 계약금액÷매출액으로 총비율 계산 (연환산 없음).
        assertEquals("매출 대비 90.0%",
                DocumentParser.salesRatioLabel(45_000_000_000L, java.util.OptionalLong.of(50_000_000_000L), null));
        assertEquals("매출 대비 50.0%",
                DocumentParser.salesRatioLabel(50_000_000_000L, java.util.OptionalLong.of(100_000_000_000L), null));
    }

    @Test
    void 명시비율도_매출도_없으면_null() {
        assertNull(DocumentParser.salesRatioLabel(50_000_000_000L, java.util.OptionalLong.empty(), null));
    }

    @Test
    void salesRatioValue_공시명시값_우선() {
        // 자동매매 트리거용 숫자값 — 명시값(30%)이 있으면 계약÷매출을 무시하고 그대로 쓴다.
        java.util.OptionalDouble v =
                DocumentParser.salesRatioValue(45_000_000_000L, java.util.OptionalLong.of(50_000_000_000L), 30.0);
        assertTrue(v.isPresent());
        assertEquals(30.0, v.getAsDouble(), 0.001);
    }

    @Test
    void salesRatioValue_명시없으면_계약나누기매출() {
        java.util.OptionalDouble v =
                DocumentParser.salesRatioValue(50_000_000_000L, java.util.OptionalLong.of(100_000_000_000L), null);
        assertTrue(v.isPresent());
        assertEquals(50.0, v.getAsDouble(), 0.001);
    }

    @Test
    void salesRatioValue_명시도_매출도_없으면_empty() {
        assertTrue(DocumentParser.salesRatioValue(50_000_000_000L, java.util.OptionalLong.empty(), null).isEmpty());
        // 매출 0도 나눗셈 불가 → empty.
        assertTrue(DocumentParser.salesRatioValue(50_000_000_000L, java.util.OptionalLong.of(0L), null).isEmpty());
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
