package com.example.dart.util;

import com.example.dart.filter.NewsFilter;

import java.util.regex.Pattern;

/**
 * DART와 KIND가 같은 공시를 각각 게시하므로, 회사명+제목 정규화 키로 교차 중복을 막는다.
 * 두 폴러가 공유 SeenStore("seen_disclosure_keys.txt")에 이 키를 기록해 먼저 잡은 쪽만 알린다.
 */
public final class DisclosureKeys {

    /** "[기재정정]", "[첨부추가]" 같은 대괄호 접두어 — 시스템마다 붙는 방식이 달라 키에서 제거. */
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^(\\s*\\[[^\\]]*\\])+");

    private DisclosureKeys() {}

    public static String of(String corpName, String title) {
        String stripped = title == null ? "" : BRACKET_PREFIX.matcher(title).replaceFirst("");
        return NewsFilter.normalize(corpName == null ? "" : corpName)
                + "|" + NewsFilter.normalize(stripped);
    }
}
