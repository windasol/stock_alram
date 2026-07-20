package com.example.dart.econcal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EconCalendarComposer 순수 포맷 로직 — 날짜 그룹핑·정렬·아이콘·라벨을 네트워크 없이 검증한다. */
class EconCalendarComposerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 16);   // 목요일
    private static final LocalDate TO = LocalDate.of(2026, 7, 21);     // 화요일

    @Test
    void 날짜별로_묶고_하루안에서는_FOMC_지표_실적_순() {
        List<EconEvent> events = List.of(
                new EconEvent(EventType.EARNINGS, FROM, "ASML 실적", "장전"),
                EconEvent.of(EventType.ECONOMIC, FROM, "🇺🇸 소비자물가(CPI)"),
                new EconEvent(EventType.FOMC, TO, "FOMC 정례회의", "금리 결정"));

        String msg = EconCalendarComposer.compose(events, FROM, TO);

        String expected = """
                📅 **향후 주요 일정** (7/16 (목) ~ 7/21 (화))

                **7/16 (목)**
                📊 🇺🇸 소비자물가(CPI)
                🏢 ASML 실적 (장전)

                **7/21 (화)**
                🏛️ FOMC 정례회의 (금리 결정)""";
        assertEquals(expected, msg);
    }

    @Test
    void 구간_밖_이벤트는_무시() {
        List<EconEvent> events = List.of(
                EconEvent.of(EventType.ECONOMIC, FROM.minusDays(1), "이전"),
                EconEvent.of(EventType.ECONOMIC, TO.plusDays(1), "이후"));
        String msg = EconCalendarComposer.compose(events, FROM, TO);
        assertTrue(msg.contains("표시할 주요 지표·실적 일정이 없습니다"), msg);
    }

    @Test
    void 이벤트가_없으면_안내문구() {
        String msg = EconCalendarComposer.compose(List.of(), FROM, TO);
        assertTrue(msg.startsWith("📅 **향후 주요 일정** (7/16 (목) ~ 7/21 (화))"), msg);
        assertTrue(msg.contains("표시할 주요 지표·실적 일정이 없습니다"), msg);
    }

    @Test
    void 부가정보_없으면_괄호_생략() {
        assertEquals("📊 GDP", EconCalendarComposer.line(EconEvent.of(EventType.ECONOMIC, FROM, "GDP")));
        assertEquals("🏢 TSM 실적 (장마감후)",
                EconCalendarComposer.line(new EconEvent(EventType.EARNINGS, FROM, "TSM 실적", "장마감후")));
    }

    @Test
    void 날짜라벨은_월일과_한글요일() {
        assertEquals("7/16 (목)", EconCalendarComposer.dateLabel(FROM));
        assertEquals("7/21 (화)", EconCalendarComposer.dateLabel(TO));
    }
}
