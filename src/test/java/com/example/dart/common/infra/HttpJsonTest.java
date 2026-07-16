package com.example.dart.common.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpJson — 헤더 쌍 전달과 JSON 파싱을 JDK 내장 HttpServer로 검증한다(외부 의존 없음).
 */
class HttpJsonTest {

    private HttpServer server;
    private final AtomicReference<String> seenHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenHeader.set(exchange.getRequestHeaders().getFirst("X-Test"));
            byte[] body = "{\"ok\":true,\"n\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void get은_헤더쌍을_요청에_싣는다() throws Exception {
        HttpResponse<String> res = HttpJson.get(HttpClient.newHttpClient(), uri(),
                Duration.ofSeconds(5), "X-Test", "hello");
        assertEquals(200, res.statusCode());
        assertEquals("hello", seenHeader.get());
    }

    @Test
    void getJson은_응답본문을_트리로_파싱한다() throws Exception {
        JsonNode root = HttpJson.getJson(HttpClient.newHttpClient(), uri(), Duration.ofSeconds(5));
        assertTrue(root.path("ok").asBoolean());
        assertEquals(42, root.path("n").asInt());
    }

    @Test
    void 홀수개_헤더인자는_마지막_짝없는_값을_무시한다() throws Exception {
        // 짝이 맞지 않는 마지막 인자("dangling")는 조용히 건너뛴다 — 예외 없이 요청이 나간다.
        HttpResponse<String> res = HttpJson.get(HttpClient.newHttpClient(), uri(),
                Duration.ofSeconds(5), "X-Test", "paired", "dangling");
        assertEquals(200, res.statusCode());
        assertEquals("paired", seenHeader.get());
    }
}
