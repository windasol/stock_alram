package com.example.dart.news;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 구글뉴스 키워드 검색 RSS 피드 팩토리 — 키 없이 동작하는 네이버 검색 보완망.
 * 네이버 API에 색인되지 않는 중소매체·외신 한글판 기사를 잡는다.
 *
 * "when:1h" 연산자로 최근 1시간 기사만 받아 결과량을 줄인다 —
 * 미동작하더라도 30분 기사 나이 필터(NewsArticleFilter)가 과거 기사를 막는다.
 * 기사 link는 news.google.com/rss/articles/&lt;id&gt; 형식의 기사별 고유 URL이라
 * 기존 링크 기반 SeenStore로 중복 제거가 된다.
 */
public final class GoogleNewsFeeds {

    private static final String URL_TEMPLATE =
            "https://news.google.com/rss/search?q=%s&hl=ko&gl=KR&ceid=KR:ko";

    private GoogleNewsFeeds() {}

    public static List<RssFeed> of(List<String> keywords) {
        return keywords.stream().map(GoogleNewsFeeds::feed).toList();
    }

    private static RssFeed feed(String keyword) {
        String query = URLEncoder.encode(keyword + " when:1h", StandardCharsets.UTF_8);
        return new RssFeed("구글뉴스(" + keyword + ")", URL_TEMPLATE.formatted(query));
    }
}
