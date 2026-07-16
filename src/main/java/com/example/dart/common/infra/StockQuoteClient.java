package com.example.dart.common.infra;

import com.example.dart.common.domain.KoreanMoney;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
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
    private static final String USER_AGENT = "Mozilla/5.0";
    /** 단건 조회 요청 타임아웃(초). */
    private static final int REQUEST_TIMEOUT_SEC = 10;

    private final HttpClient httpClient;

    public StockQuoteClient() {
        this.httpClient = TrustStores.newHttpClient();
    }

    /**
     * 종목 시가총액(원). 코드 없음·조회 실패·파싱 실패 시 empty — 보강을 멈추지 않는다.
     */
    public OptionalLong marketCapWon(String stockCode) {
        Optional<String> body = getBody(stockCode, "시총 조회");
        return body.isPresent() ? parseMarketCap(body.get()) : OptionalLong.empty();
    }

    /**
     * integration API GET 공통 — 종목코드로 조회해 200이면 응답 본문(String)을, 코드 없음·비200·오류면 empty.
     * @param label 실패 로그 구분용 라벨(예: "시총 조회").
     */
    private Optional<String> getBody(String stockCode, String label) {
        if (stockCode == null || stockCode.isBlank()) return Optional.empty();
        try {
            HttpResponse<String> response = HttpJson.get(httpClient,
                    URI.create(String.format(API, stockCode)), Duration.ofSeconds(REQUEST_TIMEOUT_SEC),
                    "User-Agent", USER_AGENT);
            if (response.statusCode() != 200) {
                log.warn("{} 실패 (code={}): status={}", label, stockCode, response.statusCode());
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (Exception e) {
            log.warn("{} 중 오류 (code={})", label, stockCode, e);
            return Optional.empty();
        }
    }

    /**
     * 전일 종가(원) — 공시 후 추적이 "전일 종가 대비 당일 등락률"을 계산하는 기준값.
     * 정규장·NXT 모두 당일 등락률 기준이 전일 종가라 한 번만 잡으면 된다. 추적당 1회(start 시점) 조회.
     * 코드 없음·조회 실패·파싱 실패 시 empty — 그 경우 등락률 병기를 생략할 뿐 추적은 계속한다.
     */
    public OptionalLong previousCloseWon(String stockCode) {
        Optional<String> body = getBody(stockCode, "전일 종가 조회");
        return body.isPresent() ? parsePreviousClose(body.get()) : OptionalLong.empty();
    }

    /**
     * realtime 응답에서 현재가(원)만 파싱한다. (테스트 호환용 — 내부는 parseSnapshot에 위임)
     */
    OptionalLong parseCurrentPrice(String json) {
        return parseSnapshot(json).price();
    }

    /**
     * realtime 응답을 스냅샷으로 파싱한다.
     *
     * 정규장 종가(closePrice)는 NXT 연장세션(프리 08:00~09:00·애프터 15:30~20:00) 동안 고정돼,
     * 그 시간대엔 모든 샘플이 같은 값 → 등락률 0%로 잘못 찍힌다. 그래서 연장세션이 열려 있으면
     * (overMarketPriceInfo.overMarketStatus=OPEN) 그 시간대 실시간가(overPrice)를 우선 쓴다.
     * 정규장(09:00~15:30)엔 연장세션이 닫혀 있어 closePrice가 실시간가다.
     *
     * nxtOpen: 연장세션 OPEN 여부. execPresent: 그 세션의 체결가(overPrice)가 실제로 들어와 있는지
     * (false면 NXT가 열렸어도 아직 체결이 없어 closePrice로 폴백한 상태 — 호가 보완 대상).
     */
    PriceSnapshot parseSnapshot(String json) {
        try {
            JsonNode datas = HttpJson.MAPPER.readTree(json).path("datas");
            if (datas.isArray() && !datas.isEmpty()) {
                JsonNode d = datas.get(0);
                JsonNode over = d.path("overMarketPriceInfo");
                boolean nxtOpen = "OPEN".equals(over.path("overMarketStatus").asText());
                if (nxtOpen) {
                    OptionalLong overWon = wonOf(over.path("overPrice").asText(""));
                    if (overWon.isPresent()) {
                        return new PriceSnapshot(overWon, true, true);   // NXT 프리·애프터마켓 실시간 체결가
                    }
                    // NXT 열렸지만 체결가 없음 → 종가로 폴백하되 execPresent=false로 표시
                    return new PriceSnapshot(wonOf(d.path("closePrice").asText("")), true, false);
                }
                return new PriceSnapshot(wonOf(d.path("closePrice").asText("")), false, false);
            }
        } catch (Exception e) {
            log.warn("현재가 JSON 파싱 실패", e);
        }
        return PriceSnapshot.empty();
    }

    /**
     * 현재가 + 세션 컨텍스트.
     *
     * @param price       현재가(원). 파싱 실패 시 empty.
     * @param nxtOpen     NXT 연장세션 OPEN 여부.
     * @param execPresent NXT 세션의 체결가(overPrice)가 실제로 존재하는지.
     */
    public record PriceSnapshot(OptionalLong price, boolean nxtOpen, boolean execPresent) {
        static PriceSnapshot empty() {
            return new PriceSnapshot(OptionalLong.empty(), false, false);
        }
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

    /** integration 응답의 totalInfos[code=lastClosePrice].value("322,500")를 원으로 파싱. (테스트용 패키지 가시성) */
    OptionalLong parsePreviousClose(String json) {
        try {
            for (JsonNode info : HttpJson.MAPPER.readTree(json).path("totalInfos")) {
                if ("lastClosePrice".equals(info.path("code").asText())) {
                    return wonOf(info.path("value").asText());
                }
            }
        } catch (Exception e) {
            log.warn("전일 종가 JSON 파싱 실패", e);
        }
        return OptionalLong.empty();
    }

    /** integration 응답의 totalInfos[code=marketValue].value("1,970조 1,959억")를 원으로 파싱. */
    OptionalLong parseMarketCap(String json) {
        try {
            for (JsonNode info : HttpJson.MAPPER.readTree(json).path("totalInfos")) {
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
