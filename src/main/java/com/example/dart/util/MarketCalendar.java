package com.example.dart.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;

/**
 * 국내 증시(KRX) 거래일 판정 — 주말과 공휴일(임시공휴일·연말 폐장일 포함)에 폴러가 stale 데이터로
 * 동작하지 않게 게이트로 쓴다.
 *
 * <p>공휴일 집합은 외부에서 주입한다(테스트는 날짜 집합을 직접 넣고, 운영은 {@link #loadFile}로 캐시 파일을,
 * 또는 KIS 휴장일 조회 결과를 넣는다). 공휴일 데이터가 비어 있으면 주말만 걸러 기존 동작으로 안전하게 폴백한다.
 */
public class MarketCalendar {

    private static final Logger log = LoggerFactory.getLogger(MarketCalendar.class);
    /** 캐시 파일 한 줄 형식 — yyyyMMdd (예: 20260101). */
    public static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Set<LocalDate> holidays;

    public MarketCalendar(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    /** 주말도 공휴일도 아니면 거래일. */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        return !holidays.contains(date);
    }

    /** 평일이지만 KRX 휴장인 날(공휴일·임시공휴일·연말 폐장일). */
    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    /** 캐시 파일(yyyyMMdd 한 줄씩)에서 휴장일 집합을 읽는다. 없거나 손상되면 빈 집합. */
    public static Set<LocalDate> loadFile(Path file) {
        Set<LocalDate> set = new TreeSet<>();
        if (file == null || !Files.exists(file)) return set;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                try {
                    set.add(LocalDate.parse(s, FILE_FMT));
                } catch (Exception ignore) {
                    log.debug("휴장일 캐시 파싱 스킵: {}", s);
                }
            }
        } catch (IOException e) {
            log.warn("{} 로드 실패 — 주말만 거른다", file, e);
        }
        return set;
    }

    /** 휴장일 집합을 캐시 파일에 yyyyMMdd 정렬로 기록한다(KIS 조회 성공 시 최신화용). */
    public static void saveFile(Path file, Set<LocalDate> holidays) {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (LocalDate d : new TreeSet<>(holidays)) {
            sb.append(d.format(FILE_FMT)).append(System.lineSeparator());
        }
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("{} 저장 실패", file, e);
        }
    }
}
