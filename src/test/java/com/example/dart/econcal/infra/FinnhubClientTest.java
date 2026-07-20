package com.example.dart.econcal.infra;

import com.example.dart.econcal.domain.EconEvent;
import com.example.dart.econcal.domain.EventType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FinnhubClient 순수 파싱·워치리스트 필터·시점 라벨 — 네트워크 없이 earningsCalendar 처리만 검증한다. */
class FinnhubClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 16);
    private static final LocalDate TO = LocalDate.of(2026, 7, 21);
    private static final List<String> WATCH = List.of("ASML", "TSM");

    @Test
    void 워치리스트_종목의_구간안_실적만_이벤트로() {
        String json = """
                {"earningsCalendar":[
                  {"date":"2026-07-16","symbol":"ASML","hour":"bmo"},
                  {"date":"2026-07-17","symbol":"TSM","hour":"amc"},
                  {"date":"2026-07-18","symbol":"RANDOM","hour":"dmh"},
                  {"date":"2026-07-30","symbol":"ASML","hour":"bmo"}
                ]}
                """;
        List<EconEvent> events = FinnhubClient.parse(json, FROM, TO, WATCH);
        assertEquals(2, events.size());   // RANDOM은 워치리스트 밖, 7/30은 구간 밖
        assertEquals(EventType.EARNINGS, events.get(0).type());
        assertEquals("ASML 실적", events.get(0).title());
        assertEquals("장전", events.get(0).detail());
        assertEquals("TSM 실적", events.get(1).title());
        assertEquals("장마감후", events.get(1).detail());
    }

    @Test
    void 심볼_소문자여도_대문자_워치리스트와_매칭() {
        String json = "{\"earningsCalendar\":[{\"date\":\"2026-07-16\",\"symbol\":\"asml\",\"hour\":\"bmo\"}]}";
        assertEquals(1, FinnhubClient.parse(json, FROM, TO, WATCH).size());
    }

    @Test
    void 깨진_JSON이면_빈목록() {
        assertTrue(FinnhubClient.parse("nope", FROM, TO, WATCH).isEmpty());
    }

    @Test
    void 시점_라벨_매핑() {
        assertEquals("장전", FinnhubClient.hourLabel("bmo"));
        assertEquals("장마감후", FinnhubClient.hourLabel("amc"));
        assertEquals("장중", FinnhubClient.hourLabel("dmh"));
        assertEquals("시간미정", FinnhubClient.hourLabel(""));
        assertEquals("시간미정", FinnhubClient.hourLabel(null));
    }
}
