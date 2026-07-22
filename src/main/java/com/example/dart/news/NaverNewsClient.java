package com.example.dart.news;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.common.infra.TrustStores;
import com.example.dart.common.text.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 네이버 뉴스 검색 Open API 클라이언트.
 * 키워드 검색 결과를 최신순으로 받아온다. 일 호출 한도 25,000회.
 */
public class NaverNewsClient {

    private static final Logger log = LoggerFactory.getLogger(NaverNewsClient.class);
    private static final String API_URL = "https://openapi.naver.com/v1/search/news.json";

    /** 네이버 pubDate 형식: "Mon, 26 Sep 2016 07:50:00 +0900" */
    private static final DateTimeFormatter PUB_DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final String clientId;
    private final String clientSecret;
    private final HttpClient httpClient;

    public NaverNewsClient(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.httpClient = TrustStores.newHttpClient();
    }

    /** 키워드로 최신 뉴스를 검색한다. 실패 시 빈 목록 — 폴링 루프를 멈추지 않는다. */
    public List<NewsArticle> search(String query) {
        String url = API_URL
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&display=20&sort=date";

        try {
            HttpResponse<String> response = HttpJson.get(httpClient, URI.create(url), Duration.ofSeconds(15),
                    "X-Naver-Client-Id", clientId,
                    "X-Naver-Client-Secret", clientSecret);

            if (response.statusCode() == 429) {
                log.warn("네이버 API 호출 한도 초과 (query={})", query);
                return Collections.emptyList();
            }
            if (response.statusCode() != 200) {
                String body = response.body();
                log.warn("네이버 뉴스 검색 실패: status={}, body={}", response.statusCode(),
                        Texts.ellipsize(body, 300));
                return Collections.emptyList();
            }

            List<NewsArticle> result = new ArrayList<>();
            for (JsonNode item : HttpJson.MAPPER.readTree(response.body()).path("items")) {
                result.add(toArticle(item));
            }
            log.debug("뉴스 조회 완료 (query={}, {}건)", query, result.size());
            return result;
        } catch (Exception e) {
            log.warn("네이버 뉴스 검색 중 오류 (query={}): {}", query, e.toString());
            return Collections.emptyList();
        }
    }

    private static NewsArticle toArticle(JsonNode item) {
        return new NewsArticle(
                "네이버",
                stripHtml(item.path("title").asText()),
                item.path("link").asText(),
                item.path("originallink").asText(),
                stripHtml(item.path("description").asText()),
                parsePubDate(item.path("pubDate").asText())
        );
    }

    /** 검색어 하이라이트 태그(&lt;b&gt;)와 HTML 엔티티(&amp;quot; 등) 제거. */
    private static String stripHtml(String html) {
        return Jsoup.parse(html).text();
    }

    private static ZonedDateTime parsePubDate(String pubDate) {
        try {
            return ZonedDateTime.parse(pubDate, PUB_DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
