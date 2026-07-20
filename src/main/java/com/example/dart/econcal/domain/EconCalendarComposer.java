package com.example.dart.econcal.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * "향후 N일 주요 일정" 다이제스트 메시지 조립 — 이벤트 목록을 날짜별로 묶어 마크다운 한 덩어리로 만든다.
 * 순수 함수(IO·네트워크 없음, §7 Composer 규칙). 정렬·그룹핑·아이콘·날짜 라벨만 담당한다.
 */
public final class EconCalendarComposer {

    private EconCalendarComposer() {}

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M/d", Locale.KOREAN);
    /** 하루 안에서 FOMC → 지표 → 실적, 같은 종류면 제목 가나다/알파벳 순. */
    private static final Comparator<EconEvent> ORDER =
            Comparator.comparing(EconEvent::date)
                    .thenComparing(e -> e.type().ordinal())
                    .thenComparing(EconEvent::title);

    /**
     * {@code [from, to]} 구간의 이벤트를 날짜별 섹션으로 렌더링한다. 구간 밖 이벤트는 무시한다.
     * 표시할 이벤트가 없으면 "일정 없음" 안내를 담은 메시지를 돌려준다(매일 1회 발송이라 생존 신호도 겸함).
     */
    public static String compose(List<EconEvent> events, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📅 **향후 주요 일정** (%s ~ %s)",
                dateLabel(from), dateLabel(to)));

        List<EconEvent> sorted = events.stream()
                .filter(e -> !e.date().isBefore(from) && !e.date().isAfter(to))
                .sorted(ORDER)
                .toList();

        if (sorted.isEmpty()) {
            sb.append("\n\n이 구간에 표시할 주요 지표·실적 일정이 없습니다.");
            return sb.toString();
        }

        LocalDate current = null;
        for (EconEvent e : sorted) {
            if (!e.date().equals(current)) {
                current = e.date();
                sb.append("\n\n**").append(dateLabel(current)).append("**");
            }
            sb.append('\n').append(line(e));
        }
        return sb.toString();
    }

    /** 한 이벤트 한 줄 — "아이콘 제목 (부가정보)". 부가정보 없으면 괄호 생략. */
    static String line(EconEvent e) {
        String base = e.type().icon() + " " + e.title();
        return e.detail().isBlank() ? base : base + " (" + e.detail() + ")";
    }

    /** "7/16 (목)" 형식. 요일은 한글 한 글자. */
    static String dateLabel(LocalDate d) {
        return MD.format(d) + " (" + weekday(d.getDayOfWeek()) + ")";
    }

    private static String weekday(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }
}
