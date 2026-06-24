package com.example.dart.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GeminiClient.extractText 단위 테스트 — 네트워크 없이 응답 본문 파싱만 검증한다.
 * (실제 호출 검증은 gemini_smoketest.ps1 이 담당.)
 */
class GeminiClientTest {

    @Test
    void 정상_응답이면_parts_텍스트를_이어붙인다() {
        String body = """
                {"candidates":[{"content":{"parts":[
                  {"text":"반도체로 자금이 몰린다. "},
                  {"text":"전반적으로 강세장."}
                ]}}]}
                """;
        assertEquals("반도체로 자금이 몰린다. 전반적으로 강세장.", GeminiClient.extractText(body));
    }

    @Test
    void 안전필터로_후보가_비면_null() {
        String body = """
                {"candidates":[{"finishReason":"SAFETY","content":{"parts":[{"text":""}]}}]}
                """;
        assertNull(GeminiClient.extractText(body));
    }

    @Test
    void candidates가_아예_없으면_null() {
        String body = """
                {"promptFeedback":{"blockReason":"OTHER"}}
                """;
        assertNull(GeminiClient.extractText(body));
    }

    @Test
    void 깨진_JSON이면_예외를_삼키고_null() {
        assertNull(GeminiClient.extractText("not-json{"));
    }

    @Test
    void 빈_문자열이면_null() {
        assertNull(GeminiClient.extractText(""));
    }
}
