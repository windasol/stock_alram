package com.example.dart.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void 그라운딩_출처를_상위3개_제목으로_뽑는다() {
        String body = """
                {"candidates":[{"groundingMetadata":{"groundingChunks":[
                  {"web":{"uri":"https://a.com/1","title":"연합뉴스"}},
                  {"web":{"uri":"https://b.com/2","title":"Bloomberg"}},
                  {"web":{"uri":"https://c.com/3","title":"한국경제"}},
                  {"web":{"uri":"https://d.com/4","title":"로이터"}}
                ]}}]}
                """;
        String s = GeminiClient.extractSources(body);
        assertTrue(s.startsWith("🔎 출처: "), s);
        assertTrue(s.contains("연합뉴스") && s.contains("Bloomberg") && s.contains("한국경제"), s);
        assertFalse(s.contains("로이터"), s);   // 상위 3개만
    }

    @Test
    void 출처_제목이_없으면_uri로_대체() {
        String body = """
                {"candidates":[{"groundingMetadata":{"groundingChunks":[
                  {"web":{"uri":"https://only-uri.com/x"}}
                ]}}]}
                """;
        assertTrue(GeminiClient.extractSources(body).contains("https://only-uri.com/x"));
    }

    @Test
    void 그라운딩_메타데이터가_없으면_빈문자열() {
        assertEquals("", GeminiClient.extractSources("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]}}]}"));
        assertEquals("", GeminiClient.extractSources("not-json{"));
    }
}
