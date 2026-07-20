package com.example.dart.common.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP+JSON 공통 유틸 — 클라이언트마다 반복되던 {@code new ObjectMapper()}와
 * {@code newBuilder().uri().timeout().GET().build(); send(...)} 보일러플레이트를 한곳으로 모은다.
 *
 * <p>{@link #MAPPER}는 스레드 안전한 단일 공유 인스턴스다(클라이언트별로 따로 만들 이유가 없다).
 * {@link #get}은 GET 요청 조립·전송만 담당하고 상태코드 검사·로깅·파싱은 호출부에 남긴다 —
 * 각 API의 성공 판정(HTTP status vs. payload status)과 실패 로그 메시지가 제각각이라 그게 자연스럽다.
 * HttpClient는 인증 헤더·타임아웃이 클라이언트마다 달라 여전히 각자 보유한다(공유하는 건 로직뿐).
 */
public final class HttpJson {

    /** 프로젝트 공용 ObjectMapper — 스레드 안전, 전역 1개. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpJson() {}

    /**
     * 표준 GET 전송 — {@code uri}·{@code timeout}과 선택 헤더(키·값을 번갈아 나열)로 요청을 만들어
     * 문자열 응답을 받는다. 상태코드 검사·파싱은 호출부가 한다.
     *
     * @param headers 키·값 쌍을 번갈아 나열(예: {@code "User-Agent", "Mozilla/5.0"}). 없으면 생략.
     */
    public static HttpResponse<String> get(HttpClient client, URI uri, Duration timeout, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * GET 후 응답 본문을 JSON 트리로 파싱한다. 상태코드 검사는 하지 않는다 —
     * payload 안의 status 필드로 성공을 판정하는(또는 비200 본문을 그대로 파싱 실패로 흘리는) 호출부용.
     */
    public static JsonNode getJson(HttpClient client, URI uri, Duration timeout, String... headers)
            throws IOException, InterruptedException {
        return MAPPER.readTree(get(client, uri, timeout, headers).body());
    }

    /**
     * 표준 JSON POST 전송 — {@code Content-Type: application/json}을 기본 설정하고, {@code jsonBody}를
     * 그대로 본문으로 보낸다. 선택 헤더(키·값 번갈아)와 상태코드 검사·응답 파싱은 {@link #get}과 동일하게
     * 호출부에 맡긴다 — 성공 판정(HTTP status vs. payload status)과 실패 로그가 API마다 다르기 때문이다.
     * 폼 전송(application/x-www-form-urlencoded)은 대상이 아니다.
     *
     * @param jsonBody 직렬화 완료된 JSON 문자열(예: {@code MAPPER.writeValueAsString(node)})
     * @param headers  키·값 쌍을 번갈아 나열(예: {@code "x-goog-api-key", key}). 없으면 생략.
     */
    public static HttpResponse<String> post(HttpClient client, URI uri, String jsonBody,
                                            Duration timeout, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
