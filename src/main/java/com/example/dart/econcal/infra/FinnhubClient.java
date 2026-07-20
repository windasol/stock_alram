package com.example.dart.econcal.infra;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.common.infra.TrustStores;
import com.example.dart.econcal.domain.EconEvent;
import com.example.dart.econcal.domain.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 주요 기업 <b>실적 발표일</b>을 Finnhub earnings calendar API로 조회한다. 무료 티어로 미국 상장 종목을
 * 주며, ASML(나스닥)·TSM(NYSE ADR) 등 글로벌 대장주도 미국 상장분으로 잡힌다.
 *
 * <p>{@code *Client} 규칙(§7): HTTP/파싱만. 관심 종목(워치리스트) 필터도 여기서 한다 — 캘린더 전체는
 * 하루 수백 종목이라 워치리스트로 좁힌다. 실패 시 빈 목록(다이제스트는 나머지 소스로 계속).
 */
public class FinnhubClient {

    private static final Logger log = LoggerFactory.getLogger(FinnhubClient.class);
    private static final String API =
            "https://finnhub.io/api/v1/calendar/earnings?from=%s&to=%s&token=%s";

    private final String token;
    private final HttpClient httpClient;

    public FinnhubClient(String token) {
        this.token = token;
        this.httpClient = TrustStores.newHttpClient();
    }

    /**
     * {@code [from, to]} 구간에 실적을 발표하는, 워치리스트에 든 종목의 이벤트를 돌려준다.
     * 실패(네트워크·비200·파싱)면 빈 목록.
     */
    public List<EconEvent> upcoming(LocalDate from, LocalDate to, List<String> tickers) {
        try {
            URI uri = URI.create(String.format(API, from, to, token));
            HttpResponse<String> res = HttpJson.get(httpClient, uri, Duration.ofSeconds(10),
                    "User-Agent", "stock_alram");
            if (res.statusCode() != 200) {
                log.warn("Finnhub 실적 일정 조회 실패: status={}", res.statusCode());
                return List.of();
            }
            List<EconEvent> events = parse(res.body(), from, to, tickers);
            log.info("Finnhub 실적 일정 {}건 (워치리스트 {}종목, 기준 {}~{})", events.size(), tickers.size(), from, to);
            return events;
        } catch (Exception e) {
            log.warn("Finnhub 실적 일정 조회 중 오류: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Finnhub earningsCalendar 응답을 파싱한다. 각 항목 {@code {date, symbol, hour}}에서 심볼이 워치리스트에
     * 있고 날짜가 구간 안이면 이벤트로 만든다. (네트워크 분리 — 테스트용 static)
     */
    static List<EconEvent> parse(String json, LocalDate from, LocalDate to, List<String> tickers) {
        Set<String> watch = upperSet(tickers);
        List<EconEvent> events = new ArrayList<>();
        try {
            JsonNode arr = HttpJson.MAPPER.readTree(json).path("earningsCalendar");
            for (JsonNode n : arr) {
                String symbol = n.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
                String dateStr = n.path("date").asText("");
                if (symbol.isEmpty() || dateStr.length() != 10) continue;
                if (!watch.isEmpty() && !watch.contains(symbol)) continue;
                LocalDate date;
                try {
                    date = LocalDate.parse(dateStr);
                } catch (Exception ignore) {
                    continue;
                }
                if (date.isBefore(from) || date.isAfter(to)) continue;
                String when = hourLabel(n.path("hour").asText(""));
                events.add(new EconEvent(EventType.EARNINGS, date, symbol + " 실적", when));
            }
        } catch (Exception e) {
            log.warn("Finnhub 실적 일정 JSON 파싱 실패: {}", e.toString());
        }
        return events;
    }

    /** Finnhub hour 코드 → 한글 시점 라벨. bmo=장전, amc=장마감후, dmh=장중, 그 외=시간미정. (테스트용 static) */
    static String hourLabel(String hour) {
        return switch (hour == null ? "" : hour.trim().toLowerCase(Locale.ROOT)) {
            case "bmo" -> "장전";
            case "amc" -> "장마감후";
            case "dmh" -> "장중";
            default -> "시간미정";
        };
    }

    private static Set<String> upperSet(List<String> tickers) {
        Set<String> set = new LinkedHashSet<>();
        if (tickers != null) {
            for (String t : tickers) {
                if (!t.isBlank()) set.add(t.trim().toUpperCase(Locale.ROOT));
            }
        }
        return set;
    }
}
