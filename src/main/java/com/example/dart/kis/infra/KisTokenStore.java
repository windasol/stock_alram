package com.example.dart.kis.infra;

import com.example.dart.common.infra.HttpJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * KIS OAuth 토큰 수명 관리 — 발급·파일 영속화·재사용을 담당한다. {@link KisClient}에서 분리한 이유는,
 * 토큰은 유효기간 1일이고 잦은 재발급이 한도(분당 1회)·알림톡을 유발하므로 파일에 저장해 재시작 후에도
 * 살아있는 토큰을 재사용해야 하기 때문이다 — 이 상태·정책을 조회 로직과 섞지 않는다.
 */
final class KisTokenStore {

    private static final Logger log = LoggerFactory.getLogger(KisTokenStore.class);
    private static final String TOKEN_PATH = "/oauth2/tokenP";
    /** 토큰 만료 이만큼 전에 미리 재발급한다. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(10);

    private final String appKey;
    private final String appSecret;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Path tokenFile;

    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    KisTokenStore(String appKey, String appSecret, String baseUrl, HttpClient httpClient, Path tokenFile) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.tokenFile = tokenFile;
        loadTokenFromFile();
    }

    /** 유효한 토큰을 돌려준다 — 만료 임박(마진 내)이거나 없으면 재발급한다. 단일 폴러 스레드 기준이나 방어적으로 동기화. */
    synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
            return token;
        }
        issueToken();
        return token;
    }

    private void issueToken() throws Exception {
        String body = HttpJson.MAPPER.createObjectNode()
                .put("grant_type", "client_credentials")
                .put("appkey", appKey)
                .put("appsecret", appSecret)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + TOKEN_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = HttpJson.MAPPER.readTree(response.body());
        String accessToken = root.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("KIS 토큰 발급 실패: " + response.body());
        }
        long expiresInSec = root.path("expires_in").asLong(86400);
        this.token = accessToken;
        this.tokenExpiry = Instant.now().plusSeconds(expiresInSec);
        saveTokenToFile();
        log.info("KIS 토큰 발급 완료 (만료 {})", tokenExpiry);
    }

    private void loadTokenFromFile() {
        try {
            if (!Files.exists(tokenFile)) return;
            List<String> lines = Files.readAllLines(tokenFile, StandardCharsets.UTF_8);
            if (lines.size() < 2) return;
            Instant expiry = Instant.ofEpochSecond(Long.parseLong(lines.get(1).trim()));
            if (Instant.now().isBefore(expiry.minus(TOKEN_REFRESH_MARGIN))) {
                this.token = lines.get(0).trim();
                this.tokenExpiry = expiry;
                log.info("KIS 토큰 파일에서 재사용 (만료 {})", expiry);
            }
        } catch (Exception e) {
            log.warn("KIS 토큰 파일 읽기 실패 — 새로 발급한다: {}", e.toString());
        }
    }

    private void saveTokenToFile() {
        try {
            Files.write(tokenFile,
                    List.of(token, Long.toString(tokenExpiry.getEpochSecond())),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("KIS 토큰 파일 저장 실패 (재시작 시 재발급됨): {}", e.toString());
        }
    }
}
