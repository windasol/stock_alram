package com.example.dart.util;

import com.example.dart.filter.NewsFilter;

import java.util.regex.Pattern;

/**
 * DART와 KIND가 같은 공시를 각각 게시하므로, 날짜+회사명+제목 정규화 키로 교차 중복을 막는다.
 * 두 폴러가 공유 SeenStore("seen_disclosure_keys.txt")에 이 키를 기록해 먼저 잡은 쪽만 알린다.
 *
 * 날짜(yyyyMMdd)를 키에 포함하는 이유: 같은 회사가 같은 제목의 공시(예: "단일판매ㆍ공급계약체결")를
 * 며칠 간격으로 반복해서 내는 일이 흔하다. 날짜가 없으면 한 번 본 회사·제목 조합이 영구히 중복으로
 * 차단돼 이후 새 공시가 모두 묻힌다. 날짜를 넣으면 같은 날 DART·KIND 동시 게시만 합치고
 * 다른 날 새 공시는 정상 통과한다.
 */
public final class DisclosureKeys {

    /** "[기재정정]", "[첨부추가]" 같은 대괄호 접두어 — 시스템마다 붙는 방식이 달라 키에서 제거. */
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^(\\s*\\[[^\\]]*\\])+");

    private DisclosureKeys() {}

    /**
     * @param date 공시 게시일(yyyyMMdd). DART는 rcept_dt, KIND는 폴링 시점의 오늘 날짜를 넘긴다.
     */
    public static String of(String date, String corpName, String title) {
        String stripped = title == null ? "" : BRACKET_PREFIX.matcher(title).replaceFirst("");
        return (date == null ? "" : date)
                + "|" + NewsFilter.normalize(corpName == null ? "" : corpName)
                + "|" + NewsFilter.normalize(stripped);
    }
}
