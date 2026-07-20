package com.example.dart.econcal.infra;

import com.example.dart.econcal.domain.EconEvent;
import com.example.dart.econcal.domain.EventType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FredClient 순수 파싱·필터·라벨 매핑 — 네트워크 없이 FRED release_dates 응답 처리만 검증한다. */
class FredClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 16);
    private static final LocalDate TO = LocalDate.of(2026, 7, 21);
    private static final List<String> KEYWORDS = List.of("Consumer Price Index", "Producer Price Index");

    @Test
    void 키워드에_걸리고_구간안인_지표만_이벤트로() {
        String json = """
                {"release_dates":[
                  {"release_id":10,"release_name":"Consumer Price Index","date":"2026-07-16"},
                  {"release_id":46,"release_name":"Producer Price Index","date":"2026-07-15"},
                  {"release_id":99,"release_name":"Some Obscure Index","date":"2026-07-17"}
                ]}
                """;
        List<EconEvent> events = FredClient.parse(json, FROM, TO, KEYWORDS);
        assertEquals(1, events.size());   // PPI는 구간 이전(7/15), Obscure는 키워드 불일치
        EconEvent e = events.get(0);
        assertEquals(EventType.ECONOMIC, e.type());
        assertEquals(LocalDate.of(2026, 7, 16), e.date());
        assertEquals("🇺🇸 소비자물가(CPI)", e.title());
    }

    @Test
    void 같은_날짜_같은_지표는_한번만() {
        String json = """
                {"release_dates":[
                  {"release_name":"Consumer Price Index","date":"2026-07-16"},
                  {"release_name":"Consumer Price Index","date":"2026-07-16"}
                ]}
                """;
        assertEquals(1, FredClient.parse(json, FROM, TO, KEYWORDS).size());
    }

    @Test
    void 깨진_JSON이면_빈목록() {
        assertTrue(FredClient.parse("not-json{", FROM, TO, KEYWORDS).isEmpty());
    }

    @Test
    void 키워드_매칭은_대소문자_무시_부분일치() {
        assertTrue(FredClient.matchesAny("Advance Retail Sales", List.of("retail")));
        assertFalse(FredClient.matchesAny("Housing Starts", List.of("retail", "cpi")));
        assertTrue(FredClient.matchesAny("아무거나", List.of()));   // 키워드 비면 전부 통과
    }

    @Test
    void 한글라벨_매핑() {
        assertEquals("소비자물가(CPI)", FredClient.koreanLabel("Consumer Price Index"));
        assertEquals("생산자물가(PPI)", FredClient.koreanLabel("Producer Price Index"));
        assertEquals("고용보고서(비농업)", FredClient.koreanLabel("Employment Situation"));
        assertEquals("Unknown Release", FredClient.koreanLabel("Unknown Release"));   // 미매핑은 원문
    }
}
