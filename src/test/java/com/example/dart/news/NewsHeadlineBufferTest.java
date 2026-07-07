package com.example.dart.news;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsHeadlineBufferTest {

    private static NewsArticle article(String title) {
        return new NewsArticle("출처", title, "https://ex.com/" + title, null, "desc", null);
    }

    @Test
    void recent는_시간창_안의_기사만_최신순으로_돌려준다() {
        NewsHeadlineBuffer buf = new NewsHeadlineBuffer();
        Instant base = Instant.parse("2026-07-07T04:00:00Z");
        buf.addAt(article("오래된"), base.minus(Duration.ofMinutes(90)));  // 창 밖
        buf.addAt(article("첫번째"), base.minus(Duration.ofMinutes(40)));
        buf.addAt(article("두번째"), base.minus(Duration.ofMinutes(10)));

        List<NewsArticle> recent = buf.recentAsOf(Duration.ofMinutes(60), 10, base);

        // 창(60분) 밖 "오래된"은 빠지고, 최신순으로 두번째→첫번째
        assertEquals(2, recent.size());
        assertEquals("두번째", recent.get(0).title());
        assertEquals("첫번째", recent.get(1).title());
    }

    @Test
    void recent는_max건까지만_돌려준다() {
        NewsHeadlineBuffer buf = new NewsHeadlineBuffer();
        Instant base = Instant.parse("2026-07-07T04:00:00Z");
        for (int i = 0; i < 10; i++) {
            buf.addAt(article("기사" + i), base.minus(Duration.ofSeconds(10L * (10 - i))));
        }
        assertEquals(3, buf.recentAsOf(Duration.ofMinutes(60), 3, base).size());
    }

    @Test
    void 상한_초과분은_오래된것부터_버린다() {
        NewsHeadlineBuffer buf = new NewsHeadlineBuffer();
        Instant base = Instant.parse("2026-07-07T04:00:00Z");
        // 상한(500) + 여유분을 같은 시각대에 쌓아 보존창엔 안 걸리게 하고 상한만 검증
        for (int i = 0; i < 600; i++) {
            buf.addAt(article("a" + i), base.minus(Duration.ofSeconds(1)));
        }
        assertTrue(buf.size() <= 500, "size=" + buf.size());
    }
}
