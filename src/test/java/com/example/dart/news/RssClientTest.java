package com.example.dart.news;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RssClientTest {

    @Test
    void 국내_피드_표준_오프셋을_파싱한다() {
        ZonedDateTime t = RssClient.parsePubDate("Fri, 12 Jun 2026 09:04:55 +0900");
        assertEquals(ZonedDateTime.of(2026, 6, 12, 9, 4, 55, 0, ZoneOffset.ofHours(9)).toInstant(),
                t.toInstant());
    }

    @Test
    void 매일경제_콜론_오프셋을_파싱한다() {
        ZonedDateTime t = RssClient.parsePubDate("Fri, 12 Jun 2026 11:03:35 +09:00");
        assertEquals(ZonedDateTime.of(2026, 6, 12, 11, 3, 35, 0, ZoneOffset.ofHours(9)).toInstant(),
                t.toInstant());
    }

    @Test
    void 정책브리핑_구글뉴스_GMT_표기를_파싱한다() {
        ZonedDateTime t = RssClient.parsePubDate("Fri, 12 Jun 2026 04:37:16 GMT");
        assertEquals(ZonedDateTime.of(2026, 6, 12, 4, 37, 16, 0, ZoneOffset.UTC).toInstant(),
                t.toInstant());
    }

    @Test
    void 시간대_없는_로컬_표기는_KST로_간주한다() {
        ZonedDateTime t = RssClient.parsePubDate("2026-06-12 13:00:00");
        assertEquals(ZonedDateTime.of(2026, 6, 12, 13, 0, 0, 0, ZoneOffset.ofHours(9)).toInstant(),
                t.toInstant());
    }

    @Test
    void 파싱_불가능한_표기는_null() {
        assertNull(RssClient.parsePubDate("어제 오후"));
        assertNull(RssClient.parsePubDate(""));
        assertNull(RssClient.parsePubDate(null));
    }
}
