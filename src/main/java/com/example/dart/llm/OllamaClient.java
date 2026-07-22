package com.example.dart.llm;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.common.infra.TrustStores;
import com.example.dart.common.text.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 로컬 Ollama 서버(기본 http://localhost:11434) 클라이언트 — 시장 분석 요약문 생성에 쓴다.
 *
 * 내 PC에서 도는 무료 오픈소스 LLM이라 API 키·인증이 없다. KIS·네이버 클라이언트와 같은
 * HttpClient 패턴을 쓴다. 기본은 로컬 http(localhost:11434)라 SSL 검사와 무관하지만,
 * OLLAMA_BASE_URL을 원격 https로 지정한 경우까지 대응하도록 공용 빌더(TrustStores)를 경유한다.
 *
 * CPU 추론은 모델·사양에 따라 수십 초~수 분이 걸리므로 요청 타임아웃을 넉넉히 둔다.
 * 서버 미기동·모델 미설치 등 실패 시 null을 반환해 호출부가 리포트를 건너뛰게 한다(앱을 멈추지 않는다).
 */
public class OllamaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public OllamaClient(String baseUrl, String model) {
        this(baseUrl, model, Duration.ofSeconds(240));
    }

    public OllamaClient(String baseUrl, String model, Duration requestTimeout) {
        // 끝 슬래시 제거 — baseUrl + "/api/chat" 결합 시 중복 슬래시 방지.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.requestTimeout = requestTimeout;
        // 공용 빌더(회사망 SSL 검사 대응 신뢰저장소)를 쓰되, 로컬 서버 미기동을 빨리 감지하도록 연결 타임아웃은 5초로 덮어쓴다.
        this.httpClient = TrustStores.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String model() {
        return model;
    }

    /**
     * 시스템·사용자 프롬프트로 한 번 대화를 돌려 응답 텍스트를 받는다.
     * temperature를 낮게 둬(0.3) 사실 기반 요약이 흔들리지 않게 한다.
     *
     * @return 모델 응답 텍스트. 실패(서버 미기동·모델 없음·타임아웃 등) 시 null.
     */
    public String chat(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = HttpJson.MAPPER.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            ObjectNode options = body.putObject("options");
            options.put("temperature", 0.3);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpResponse<String> response = HttpJson.post(
                    httpClient, URI.create(baseUrl + "/api/chat"),
                    HttpJson.MAPPER.writeValueAsString(body), requestTimeout);
            if (response.statusCode() != 200) {
                log.warn("Ollama 응답 실패: status={}, body={}", response.statusCode(), Texts.ellipsize(response.body(), 200));
                return null;
            }
            JsonNode content = HttpJson.MAPPER.readTree(response.body()).path("message").path("content");
            String text = content.asText("").trim();
            if (text.isEmpty()) {
                log.warn("Ollama 응답이 비어 있음 (모델 {} 확인)", model);
                return null;
            }
            return text;
        } catch (Exception e) {
            // 서버 미기동(연결 거부)·모델 미설치·타임아웃 등 — 리포트만 건너뛰고 앱은 계속 돈다.
            log.warn("Ollama 호출 실패 ({} 모델 {}): {}", baseUrl, model, e.toString());
            return null;
        }
    }

}
