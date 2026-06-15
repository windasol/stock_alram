package com.example.dart.news;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleNewsFeedsTest {

    @Test
    void 키워드를_검색_RSS_피드로_만든다() {
        List<RssFeed> feeds = GoogleNewsFeeds.of(List.of("수주", "FDA 승인"));

        assertEquals(2, feeds.size());
        assertEquals("구글뉴스(수주)", feeds.get(0).name());
        assertTrue(feeds.get(0).url().startsWith("https://news.google.com/rss/search?q="));
        assertTrue(feeds.get(0).url().endsWith("&hl=ko&gl=KR&ceid=KR:ko"));
        // 한글·공백·when 연산자가 인코딩되어야 한다
        assertEquals("https://news.google.com/rss/search?q=%EC%88%98%EC%A3%BC+when%3A1h&hl=ko&gl=KR&ceid=KR:ko",
                feeds.get(0).url());
        assertEquals("구글뉴스(FDA 승인)", feeds.get(1).name());
    }

    @Test
    void 빈_키워드_목록이면_빈_피드_목록() {
        assertEquals(List.of(), GoogleNewsFeeds.of(List.of()));
    }
}
