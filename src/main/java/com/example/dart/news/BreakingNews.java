package com.example.dart.news;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 제목 말머리 마커로 "속보" 여부를 판정한다.
 *
 * RSS·네이버 모두 제목에서 HTML 태그만 제거하고 [속보] 같은 대괄호 텍스트는 보존하므로
 * (RssClient.toArticle / NaverNewsClient.toArticle), 말머리 마커가 속보를 가리키는
 * 유일하고 신뢰할 수 있는 신호다. 본문에 흔한 일반어("긴급 점검" 등)를 오인하지 않도록
 * 반드시 괄호류([]·&lt;&gt;·()·【】)로 감싼 형태만 인정한다.
 *
 * 매체마다 표기가 달라(속보·긴급·특보·플래시…) 키워드 목록은 설정(NEWS_BREAKING_KEYWORDS)으로
 * 받는다. 키워드는 괄호 안 어디에 있어도 인정한다([긴급속보]·[속보2보] 등). 더해서
 * 연합뉴스식 보도 차수 말머리([1보]·[2보]…)도 속보 시리즈이므로 자동 인식한다 —
 * 단, 정리성 [종합2보]는 차수 앞에 다른 글자가 있어 차수 패턴에 걸리지 않는다.
 */
public final class BreakingNews {

    /** 괄호류 여는/닫는 문자. */
    private static final String OPEN = "[\\[<(【]";
    private static final String CLOSE = "[\\]>)】]";
    /** 마커 한 개 안에서 다른 괄호로 넘어가지 않도록 괄호 문자를 제외한 내부 문자. */
    private static final String INNER = "[^\\[\\]<>()【】]*";

    /** 보도 차수 말머리 — [1보] [2보] … (속보 시리즈). 앞에 글자가 붙은 [종합2보]는 제외. */
    private static final Pattern REPORT_MARKER =
            Pattern.compile(OPEN + "\\s*[0-9]+보\\s*" + CLOSE);

    /** 설정된 속보 키워드를 괄호류 안에서 찾는 패턴. 키워드가 비면 null(차수만 인식). */
    private final Pattern keywordMarker;

    public BreakingNews(List<String> keywords) {
        String alt = keywords.stream()
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        this.keywordMarker = alt.isEmpty()
                ? null
                : Pattern.compile(OPEN + INNER + "(?:" + alt + ")" + INNER + CLOSE);
    }

    /** 제목에 속보 키워드 말머리 또는 보도 차수 말머리가 있으면 true. */
    public boolean isBreaking(String title) {
        if (title == null) return false;
        if (keywordMarker != null && keywordMarker.matcher(title).find()) return true;
        return REPORT_MARKER.matcher(title).find();
    }
}
