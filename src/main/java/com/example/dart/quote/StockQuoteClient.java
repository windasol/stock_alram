package com.example.dart.quote;

import com.example.dart.util.KoreanMoney;
import com.example.dart.util.TrustStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 네이버 금융 비공식 JSON API로 종목 시가총액을 조회한다.
 * 공시 보강(시총 대비 계약 규모) 1회성 조회용 — 시세 상시 감시가 아니다.
 * 회사망 SSL 검사 프록시 때문에 새 HttpClient엔 TrustStores.systemDefault()를 적용한다.
 */
public class StockQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(StockQuoteClient.class);
    private static final String API = "https://m.stock.naver.com/api/stock/%s/integration";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public StockQuoteClient() {
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 종목 시가총액(원). 코드 없음·조회 실패·파싱 실패 시 empty — 보강을 멈추지 않는다.
     */
    public OptionalLong marketCapWon(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return OptionalLong.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(API, stockCode)))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("시총 조회 실패 (code={}): status={}", stockCode, response.statusCode());
                return OptionalLong.empty();
            }
            return parseMarketCap(response.body());
        } catch (Exception e) {
            log.warn("시총 조회 중 오류 (code={})", stockCode, e);
            return OptionalLong.empty();
        }
    }

    /** integration 응답의 totalInfos[code=marketValue].value("1,970조 1,959억")를 원으로 파싱. */
    OptionalLong parseMarketCap(String json) {
        try {
            for (JsonNode info : mapper.readTree(json).path("totalInfos")) {
                if ("marketValue".equals(info.path("code").asText())) {
                    return KoreanMoney.parseWon(info.path("value").asText());
                }
            }
        } catch (Exception e) {
            log.warn("시총 JSON 파싱 실패", e);
        }
        return OptionalLong.empty();
    }

    /** @return 종목코드가 유효한 상장사면 시총 조회 가능. (코넥스·비상장 corp는 stock_code가 비어 옴) */
    public static boolean hasStockCode(String stockCode) {
        return Optional.ofNullable(stockCode).map(s -> !s.isBlank()).orElse(false);
    }
}
