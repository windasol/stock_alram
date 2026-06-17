package com.example.dart.dart;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartClientTest {

    /** 실측한 014 에러 응답(application/xml, HTTP 200). */
    private static final String STATUS_014 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<result><status>014</status><message>파일이 존재하지 않습니다.</message></result>";

    @Test
    void zip시그니처면_바이트를_그대로_반환() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
        assertArrayEquals(zip, DartClient.interpretDocumentResponse(zip, "20260615000002"));
    }

    @Test
    void status014면_DocumentNotReadyException() {
        byte[] body = STATUS_014.getBytes(StandardCharsets.UTF_8);
        DocumentNotReadyException e = assertThrows(DocumentNotReadyException.class,
                () -> DartClient.interpretDocumentResponse(body, "20260616900486"));
        assertTrue(e.getMessage().contains("014"));
        assertTrue(e.getMessage().contains("20260616900486"));
    }

    @Test
    void status014가_아닌_에러는_DartException() {
        String body = "<result><status>020</status><message>요청 제한을 초과하였습니다.</message></result>";
        DartException e = assertThrows(DartException.class,
                () -> DartClient.interpretDocumentResponse(body.getBytes(StandardCharsets.UTF_8), "x"));
        // 020은 NotReady가 아니어야 한다(영구 실패로 취급).
        assertTrue(!(e instanceof DocumentNotReadyException));
        assertTrue(e.getMessage().contains("020"));
    }
}
