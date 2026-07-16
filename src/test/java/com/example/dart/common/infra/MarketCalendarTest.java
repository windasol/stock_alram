package com.example.dart.common.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCalendarTest {

    @Test
    void 주말은_공휴일_데이터가_없어도_거래일이_아니다() {
        MarketCalendar cal = new MarketCalendar(Set.of());
        assertFalse(cal.isTradingDay(LocalDate.of(2026, 7, 4)));   // 토
        assertFalse(cal.isTradingDay(LocalDate.of(2026, 7, 5)));   // 일
        assertTrue(cal.isTradingDay(LocalDate.of(2026, 7, 3)));    // 금(평일, 휴장일 아님)
    }

    @Test
    void 공휴일_평일은_거래일이_아니다() {
        LocalDate newYear = LocalDate.of(2026, 1, 1);   // 신정(목)
        MarketCalendar cal = new MarketCalendar(Set.of(newYear));
        assertFalse(cal.isTradingDay(newYear));
        assertTrue(cal.isHoliday(newYear));
        assertTrue(cal.isTradingDay(LocalDate.of(2026, 1, 2)));    // 다음 평일은 거래일
    }

    @Test
    void 파일에서_휴장일을_읽고_쓴다(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("krx_holidays.txt");
        Files.writeString(file, "20260101\n20260302\n\n# 잘못된줄\n");

        Set<LocalDate> loaded = MarketCalendar.loadFile(file);
        assertTrue(loaded.contains(LocalDate.of(2026, 1, 1)));
        assertTrue(loaded.contains(LocalDate.of(2026, 3, 2)));
        assertEquals(2, loaded.size());   // 빈 줄·형식오류 줄은 스킵

        // 저장 후 재로드 왕복이 보존된다.
        Path out = dir.resolve("out.txt");
        MarketCalendar.saveFile(out, Set.of(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 1, 1)));
        List<String> lines = Files.readAllLines(out);
        assertEquals(List.of("20260101", "20260505"), lines);   // 정렬 저장
        assertEquals(loaded.size(), 2);
    }

    @Test
    void 없는_파일은_빈_집합() {
        Set<LocalDate> loaded = MarketCalendar.loadFile(Path.of("존재하지-않는-파일.txt"));
        assertTrue(loaded.isEmpty());
    }
}
