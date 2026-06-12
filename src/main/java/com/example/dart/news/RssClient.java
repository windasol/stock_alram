package com.example.dart.news;

import com.example.dart.util.TrustStores;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 언론사 RSS 피드 클라이언트. 포털 색인을 거치지 않아 네이버 검색보다 빠르다.
 * RSS 2.0의 item(title/link/description/pubDate)만 사용 — JSoup XML 파서로 충분.
 */
public class RssClient {

    private static final Logger log = LoggerFactory.getLogger(RssClient.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** "Fri, 12 Jun 2026 09:04:55 +0900" — 대부분의 피드. */
    private static final DateTimeFormatter RFC_FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    /** "2026-06-12 10:03:21" — 연합인포맥스 등 시간대 없는 피드 (KST로 간주). */
    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;

    public RssClient() {
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 피드 1개의 기사 목록. 실패 시 빈 목록 — 피드 하나가 죽어도 나머지는 돈다. */
    public List<NewsArticle> fetch(RssFeed feed) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feed.url()))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0")  // 일부 언론사는 기본 UA를 차단
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("RSS 조회 실패 ({}): status={}", feed.name(), response.statusCode());
                return Collections.emptyList();
            }

            Document doc = Jsoup.parse(response.body(), "", Parser.xmlParser());
            List<NewsArticle> result = new ArrayList<>();
            for (Element item : doc.select("item")) {
                result.add(toArticle(feed, item));
            }
            log.debug("RSS 조회 완료 ({}, {}건)", feed.name(), result.size());
            return result;
        } catch (Exception e) {
            log.warn("RSS 조회 중 오류 ({})", feed.name(), e);
            return Collections.emptyList();
        }
    }

    private static NewsArticle toArticle(RssFeed feed, Element item) {
        return new NewsArticle(
                feed.name(),
                stripHtml(item.select("title").text()),
                item.select("link").text().trim(),
                item.select("link").text().trim(),
                stripHtml(item.select("description").text()),
                parsePubDate(item.select("pubDate").text())
        );
    }

    /** description에 HTML 조각이 들어오는 피드 대응. */
    private static String stripHtml(String html) {
        return Jsoup.parse(html).text();
    }

    private static ZonedDateTime parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        String trimmed = pubDate.trim();
        try {
            return ZonedDateTime.parse(trimmed, RFC_FMT);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, LOCAL_FMT).atZone(KST);
        } catch (Exception e) {
            return null;
        }
    }
}
