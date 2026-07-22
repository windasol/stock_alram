package com.example.dart.kis.infra;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.common.infra.TrustStores;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 야후 파이낸스 비공식 차트 API 공통 클라이언트 — 심볼 하나의 현재가·전일 대비 등락률을 가져온다.
 * 미국 선물({@link UsFuturesClient})과 국내 지수·환율({@link DomesticMarketClient})이 같은 API를
 * 같은 방식으로 호출·파싱하던 중복을 한곳으로 모은다. 키 불필요(비공식·무료).
 *
 * 회사망 SSL 검사 프록시 때문에 새 HttpClient엔 TrustStores.systemDefault()를 적용한다(다른 클라이언트와 동일).
 * 실패(비200·차단·파싱실패)는 empty를 돌려 호출부가 해당 심볼을 건너뛰게 한다(앱을 멈추지 않는다).
 */
public class YahooChartClient {

    private static final Logger log = LoggerFactory.getLogger(YahooChartClient.class);
    private static final String API = "https://query1.finance.yahoo.com/v8/finance/chart/%s";

    private final HttpClient httpClient;

    public YahooChartClient() {
        this.httpClient = TrustStores.newHttpClient();
    }

    /** 야후 심볼의 스냅샷(현재가·등락률)을 조회한다. 실패면 empty. */
    public Optional<Snapshot> fetch(String symbol) {
        try {
            String url = String.format(API, URLEncoder.encode(symbol, StandardCharsets.UTF_8));
            HttpResponse<String> response = HttpJson.get(httpClient, URI.create(url), Duration.ofSeconds(10),
                    "User-Agent", "Mozilla/5.0");   // 야후는 UA 없으면 거부(429/401)
            if (response.statusCode() != 200) {
                log.warn("야후 차트 조회 실패 (symbol={}): status={}", symbol, response.statusCode());
                return Optional.empty();
            }
            return parseSnapshot(response.body());
        } catch (Exception e) {
            log.warn("야후 차트 조회 중 오류 (symbol={}): {}", symbol, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 야후 차트 응답에서 현재가와 전일 대비 등락률(%)을 뽑는다.
     * meta.regularMarketPrice 와 meta.previousClose(없으면 chartPreviousClose)로 (현재/전일-1)*100.
     * 값이 없거나 전일가 0·파싱 실패면 empty. (네트워크 분리 — 테스트용 정적 메서드)
     */
    static Optional<Snapshot> parseSnapshot(String json) {
        try {
            JsonNode meta = HttpJson.MAPPER.readTree(json).path("chart").path("result").path(0).path("meta");
            double price = meta.path("regularMarketPrice").asDouble(Double.NaN);
            double prev = meta.path("previousClose").asDouble(
                    meta.path("chartPreviousClose").asDouble(Double.NaN));
            if (Double.isNaN(price) || Double.isNaN(prev) || prev == 0.0) return Optional.empty();
            return Optional.of(new Snapshot(price, (price - prev) / prev * 100.0));
        } catch (Exception e) {
            log.warn("야후 차트 JSON 파싱 실패: {}", e.toString());
            return Optional.empty();
        }
    }

    /** 현재가(레벨) + 전일 대비 등락률(%). */
    public record Snapshot(double price, double pct) {}
}
