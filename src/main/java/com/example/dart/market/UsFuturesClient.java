package com.example.dart.market;

import com.example.dart.util.TrustStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 야후 파이낸스 비공식 차트 API로 미국 주가지수 선물의 전일 대비 등락률을 조회한다.
 * 장 흐름 리포트에 '대외(미국 선물) 여건' 한 줄을 더해, LLM이 국내 자금 흐름과 엮어 서술하도록 돕는다.
 *
 * 국내 장중엔 미국 현물장이 닫혀 있어 선물이 실시간 대외 분위기의 프록시다. 키 불필요(비공식·무료).
 * 회사망 SSL 검사 프록시 때문에 새 HttpClient엔 TrustStores.systemDefault()를 적용한다(다른 클라이언트와 동일).
 * 일부 지수 실패는 건너뛰고, 전부 실패면 null을 반환해 리포트는 국내 데이터로 계속된다(앱을 멈추지 않는다).
 */
public class UsFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(UsFuturesClient.class);
    private static final String API = "https://query1.finance.yahoo.com/v8/finance/chart/%s";

    /** 조회 대상 선물 — 야후 심볼과 표시 라벨. (S&P500·나스닥100·다우 e-mini 선물) */
    private static final List<Symbol> SYMBOLS = List.of(
            new Symbol("ES=F", "S&P"),
            new Symbol("NQ=F", "나스닥"),
            new Symbol("YM=F", "다우"));

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public UsFuturesClient() {
        this.httpClient = TrustStores.newHttpClient();
    }

    /**
     * 미국 선물 등락률 한 줄(예: "🌎 **미국 선물** | S&P +0.4%, 나스닥 +0.6%, 다우 +0.2%").
     * 일부 실패는 건너뛰고, 전부 실패(미조회·차단 등)면 null — 리포트가 이 블록을 생략한다.
     */
    public String summaryLine() {
        List<Quote> quotes = new ArrayList<>();
        for (Symbol s : SYMBOLS) {
            OptionalDouble pct = fetchChangePct(s.code());
            if (pct.isPresent()) quotes.add(new Quote(s.label(), pct.getAsDouble()));
        }
        return formatSummary(quotes);
    }

    private OptionalDouble fetchChangePct(String symbol) {
        try {
            String url = String.format(API, URLEncoder.encode(symbol, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0")   // 야후는 UA 없으면 거부(429/401)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("미국 선물 조회 실패 (symbol={}): status={}", symbol, response.statusCode());
                return OptionalDouble.empty();
            }
            return parseChangePct(response.body());
        } catch (Exception e) {
            log.warn("미국 선물 조회 중 오류 (symbol={}): {}", symbol, e.toString());
            return OptionalDouble.empty();
        }
    }

    /**
     * 야후 차트 응답에서 전일 대비 등락률(%)을 계산한다.
     * meta.regularMarketPrice 와 meta.previousClose(없으면 chartPreviousClose)로 (현재/전일-1)*100.
     * 값이 없거나 전일가 0·파싱 실패면 empty.
     */
    OptionalDouble parseChangePct(String json) {
        try {
            JsonNode meta = mapper.readTree(json).path("chart").path("result").path(0).path("meta");
            double price = meta.path("regularMarketPrice").asDouble(Double.NaN);
            double prev = meta.path("previousClose").asDouble(
                    meta.path("chartPreviousClose").asDouble(Double.NaN));
            if (Double.isNaN(price) || Double.isNaN(prev) || prev == 0.0) return OptionalDouble.empty();
            return OptionalDouble.of((price - prev) / prev * 100.0);
        } catch (Exception e) {
            log.warn("미국 선물 JSON 파싱 실패: {}", e.toString());
            return OptionalDouble.empty();
        }
    }

    /** 등락률 목록을 "🌎 **미국 선물** | S&P +0.4%, ..." 한 줄로. 빈 목록이면 null. (순수 함수 — 테스트용) */
    static String formatSummary(List<Quote> quotes) {
        if (quotes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("🌎 **미국 선물** | ");
        for (int i = 0; i < quotes.size(); i++) {
            if (i > 0) sb.append(", ");
            Quote q = quotes.get(i);
            sb.append(String.format("%s %+.1f%%", q.label(), q.pct()));
        }
        return sb.toString();
    }

    /** 야후 심볼 ↔ 표시 라벨. */
    private record Symbol(String code, String label) {}

    /** 표시 라벨 + 등락률(%). */
    record Quote(String label, double pct) {}
}
