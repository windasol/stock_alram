package com.example.dart.kind;

import com.example.dart.filter.NewsFilter;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * KIND 호재 공시 1건을 알림 메시지로 조립한다.
 * DART 알림(AlertComposer)과 같은 훑어보기 형식이되, 본문 요약 없이 헤더만 —
 * KIND는 속도가 목적이라 원문 파싱 없이 즉시 보낸다. 상세는 뷰어 링크로 확인.
 */
public class KindAlertComposer {

    private static final DateTimeFormatter DETECT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public String compose(KindDisclosure d, NewsFilter.TitleMatch match) {
        return String.format(
                "⚡ **%s · %s** | %s — %s\n공시 %s · 감지 %s · 제출인 %s · KIND 선행\n%s",
                match.category(), d.market(), d.company(), d.title(),
                d.time(), DETECT_TIME_FMT.format(ZonedDateTime.now(KST)),
                d.submitter(), d.detailUrl());
    }
}
