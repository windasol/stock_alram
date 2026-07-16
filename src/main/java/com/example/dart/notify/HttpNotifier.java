package com.example.dart.notify;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.common.infra.TrustStores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * JSON POST 기반 알림 채널의 공통 골격.
 * 전송 실패는 로그만 남기고 삼킨다 — 알림 1건의 실패가 폴링을 멈추면 안 된다.
 */
abstract class HttpNotifier implements Notifier {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final HttpClient httpClient = TrustStores.newHttpClient();

    protected void postJson(String url, Map<String, String> headers, Map<String, ?> payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(HttpJson.MAPPER.writeValueAsString(payload)));
            headers.forEach(builder::header);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.debug("메시지 전송 완료 (status={})", response.statusCode());
            } else {
                log.error("메시지 전송 실패: status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("메시지 전송 중 오류 발생", e);
        }
    }

    /** 채널별 길이 제한에 맞춰 자른다. */
    protected static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 20) + "\n...(생략)";
    }
}
