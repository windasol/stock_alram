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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 미국 경제지표 <b>발표 예정일</b>을 FRED(세인트루이스 연준) release dates API로 조회한다. 완전 무료(키 발급),
 * 공식·안정적이라 크롤링 대신 채택. {@code include_release_dates_with_no_data=true}가 아직 데이터가 없는
 * <b>미래 예정일</b>까지 포함시키는 핵심 파라미터다.
 *
 * <p>{@code *Client} 규칙(§7): HTTP/파싱만. 관심 지표 필터(release_name 키워드)와 한글 라벨 매핑도 여기서 한다.
 * 회사망 SSL 검사 프록시 대응으로 {@link TrustStores#newHttpClient()}를 쓴다(다른 클라이언트와 동일).
 * 실패 시 빈 목록을 돌려 다이제스트가 나머지 소스로 계속되게 한다(앱을 멈추지 않는다).
 */
public class FredClient {

    private static final Logger log = LoggerFactory.getLogger(FredClient.class);
    private static final String API =
            "https://api.stlouisfed.org/fred/releases/dates"
            + "?api_key=%s&file_type=json&include_release_dates_with_no_data=true"
            + "&realtime_start=%s&realtime_end=%s&sort_order=asc&order_by=release_date&limit=1000";

    /**
     * FRED release_name(영문) → 한글 표시 라벨. contains 매칭이라 실제 이름이 길어도("Advance Monthly
     * Sales for Retail...") 부분 일치로 잡는다. 미매칭이면 원문 이름을 그대로 쓴다.
     * (선언 순서대로 먼저 걸리는 항목을 쓴다 — 더 구체적인 것을 위에 둔다.)
     */
    private static final Map<String, String> KO_LABEL = new LinkedHashMap<>();
    static {
        KO_LABEL.put("consumer price index", "소비자물가(CPI)");
        KO_LABEL.put("producer price index", "생산자물가(PPI)");
        KO_LABEL.put("personal income", "개인소비지출(PCE 물가)");
        KO_LABEL.put("employment situation", "고용보고서(비농업)");
        KO_LABEL.put("gross domestic product", "GDP");
        KO_LABEL.put("retail", "소매판매");
    }

    private final String apiKey;
    private final HttpClient httpClient;

    public FredClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = TrustStores.newHttpClient();
    }

    /**
     * {@code [from, to]} 구간에 발표 예정인, {@code keywords} 중 하나를 이름에 포함하는 지표 이벤트를 돌려준다.
     * 실패(네트워크·비200·파싱)면 빈 목록.
     */
    public List<EconEvent> upcoming(LocalDate from, LocalDate to, List<String> keywords) {
        try {
            URI uri = URI.create(String.format(API, apiKey, from, to));
            HttpResponse<String> res = HttpJson.get(httpClient, uri, Duration.ofSeconds(10),
                    "User-Agent", "stock_alram");
            if (res.statusCode() != 200) {
                log.warn("FRED 경제지표 일정 조회 실패: status={}", res.statusCode());
                return List.of();
            }
            List<EconEvent> events = parse(res.body(), from, to, keywords);
            log.info("FRED 경제지표 일정 {}건 (기준 {}~{})", events.size(), from, to);
            return events;
        } catch (Exception e) {
            log.warn("FRED 경제지표 일정 조회 중 오류: {}", e.toString());
            return List.of();
        }
    }

    /**
     * FRED release_dates 응답을 파싱한다. 각 항목 {@code {release_name, date}}에서 이름이 키워드에 걸리고
     * 날짜가 구간 안이면 이벤트로 만든다. 같은 (날짜, 지표)는 한 번만. (네트워크 분리 — 테스트용 static)
     */
    static List<EconEvent> parse(String json, LocalDate from, LocalDate to, List<String> keywords) {
        List<EconEvent> events = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            JsonNode dates = HttpJson.MAPPER.readTree(json).path("release_dates");
            for (JsonNode n : dates) {
                String name = n.path("release_name").asText("").trim();
                String dateStr = n.path("date").asText("");
                if (name.isEmpty() || dateStr.length() != 10) continue;
                if (!matchesAny(name, keywords)) continue;
                LocalDate date;
                try {
                    date = LocalDate.parse(dateStr);
                } catch (Exception ignore) {
                    continue;   // 형식 이상 행은 건너뜀(부분 파싱 허용)
                }
                if (date.isBefore(from) || date.isAfter(to)) continue;
                String label = koreanLabel(name);
                if (seen.add(date + "|" + label)) {
                    events.add(EconEvent.of(EventType.ECONOMIC, date, "🇺🇸 " + label));
                }
            }
        } catch (Exception e) {
            log.warn("FRED 경제지표 일정 JSON 파싱 실패: {}", e.toString());
        }
        return events;
    }

    /** release_name이 키워드(대소문자 무시 contains) 중 하나라도 포함하면 true. 키워드가 비면 전부 통과. */
    static boolean matchesAny(String name, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (!k.isBlank() && lower.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 영문 release_name을 한글 라벨로. 매핑에 없으면 원문 그대로. (테스트용 static) */
    static String koreanLabel(String releaseName) {
        String lower = releaseName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : KO_LABEL.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return releaseName;
    }
}
