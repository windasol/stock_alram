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
    /** 실시간 현재가 — 공시 후 주가 추적용 경량 폴링 엔드포인트(키 불필요). */
    private static final String REALTIME_API =
            "https://polling.finance.naver.com/api/realtime/domestic/stock/%s";

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

    /**
     * 종목 현재가(원). 코드 없음·조회 실패·파싱 실패 시 empty — 추적을 멈추지 않는다.
     * 공시 후 주가 추적이 30초 간격으로 반복 호출하므로, 시총 조회와 달리 가벼운 실시간 엔드포인트를 쓴다.
     */
    public OptionalLong currentPriceWon(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return OptionalLong.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(REALTIME_API, stockCode)))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("현재가 조회 실패 (code={}): status={}", stockCode, response.statusCode());
                return OptionalLong.empty();
            }
            return parseCurrentPrice(response.body());
        } catch (Exception e) {
            log.warn("현재가 조회 중 오류 (code={})", stockCode, e);
            return OptionalLong.empty();
        }
    }

    /**
     * realtime 응답에서 현재가(원)를 파싱한다.
     *
     * 정규장 종가(closePrice)는 NXT 연장세션(프리 08:00~09:00·애프터 15:30~20:00) 동안 고정돼,
     * 그 시간대엔 모든 샘플이 같은 값 → 등락률 0%로 잘못 찍힌다. 그래서 연장세션이 열려 있으면
     * (overMarketPriceInfo.overMarketStatus=OPEN) 그 시간대 실시간가(overPrice)를 우선 쓴다.
     * 정규장(09:00~15:30)엔 연장세션이 닫혀 있어 closePrice가 실시간가다.
     */
    OptionalLong parseCurrentPrice(String json) {
        try {
            JsonNode datas = mapper.readTree(json).path("datas");
            if (datas.isArray() && !datas.isEmpty()) {
                JsonNode d = datas.get(0);
                JsonNode over = d.path("overMarketPriceInfo");
                if ("OPEN".equals(over.path("overMarketStatus").asText())) {
                    OptionalLong overWon = wonOf(over.path("overPrice").asText(""));
                    if (overWon.isPresent()) return overWon;   // NXT 프리·애프터마켓 실시간가
                }
                return wonOf(d.path("closePrice").asText(""));  // 정규장 실시간가(=종가 필드)
            }
        } catch (Exception e) {
            log.warn("현재가 JSON 파싱 실패", e);
        }
        return OptionalLong.empty();
    }

    /** "355,000" → 355000. 콤마·공백 제거 후 long. 빈 값·파싱 실패면 empty. */
    private static OptionalLong wonOf(String raw) {
        String v = raw.replaceAll("[,\\s]", "");
        if (v.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(v));
        } catch (NumberFormatException e) {
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
