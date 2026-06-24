package com.example.dart.llm;

import com.example.dart.util.TrustStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Google Gemini API(Google AI Studio) 클라이언트 — 장 흐름 리포트 요약을 클라우드에서 생성한다.
 *
 * 무료 한도(키만 발급하면 됨)가 넉넉해 시간당 1건 리포트엔 사실상 무료이고, 로컬 추론과 달리
 * 내 PC에 부하를 주지 않는다(렉 없음). 외부 HTTPS라 회사망 SSL 검사 프록시 대응으로
 * TrustStores.systemDefault()를 쓴다(KIS·네이버 클라이언트와 동일).
 *
 * 키는 시스템 환경변수/.env(GEMINI_API_KEY)로 주입한다. 실패 시 null을 반환해 리포트만 건너뛴다.
 */
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final ObjectMapper PARSER = new ObjectMapper();   // 응답 파싱 전용(정적·스레드 안전)

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration requestTimeout;

    public GeminiClient(String apiKey, String model) {
        this(apiKey, model, Duration.ofSeconds(60));
    }

    public GeminiClient(String apiKey, String model, Duration requestTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GEMINI_API_KEY 미설정 — 리포트 건너뜀");
            return null;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            // system_instruction — 모델 역할·제약(숫자 지어내지 말 것 등)을 분리해 전달.
            body.putObject("system_instruction").putArray("parts").addObject().put("text", systemPrompt);
            ArrayNode contents = body.putArray("contents");
            ObjectNode userTurn = contents.addObject();
            userTurn.put("role", "user");
            userTurn.putArray("parts").addObject().put("text", userPrompt);
            body.putObject("generationConfig").put("temperature", 0.3);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + model + ":generateContent"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)   // 키를 URL이 아닌 헤더로 — 로그 노출 방지
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Gemini 응답 실패: status={}, body={}", response.statusCode(), brief(response.body()));
                return null;
            }
            return extractText(response.body());
        } catch (Exception e) {
            log.warn("Gemini 호출 실패 (모델 {}): {}", model, e.toString());
            return null;
        }
    }

    /**
     * generateContent 200 응답 본문에서 요약 텍스트를 뽑는다.
     * candidates[0].content.parts[*].text 를 이어붙인다. 비어 있으면(안전필터 차단 등) 사유를 로깅하고,
     * JSON이 깨졌으면 예외를 삼키고 null을 반환한다. 네트워크와 분리된 순수 파싱 — 단위 테스트용.
     */
    static String extractText(String responseBody) {
        try {
            JsonNode root = PARSER.readTree(responseBody);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                text.append(part.path("text").asText(""));
            }
            String out = text.toString().trim();
            if (out.isEmpty()) {
                // 후보 없음 = 안전필터 차단 등. 사유를 남겨 디버깅을 돕는다.
                String reason = root.path("candidates").path(0).path("finishReason").asText(
                        root.path("promptFeedback").path("blockReason").asText("unknown"));
                log.warn("Gemini 응답이 비어 있음 (finishReason={})", reason);
                return null;
            }
            return out;
        } catch (Exception e) {
            log.warn("Gemini 응답 파싱 실패: {}", e.toString());
            return null;
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
