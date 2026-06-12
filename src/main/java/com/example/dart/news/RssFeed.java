package com.example.dart.news;

import java.util.List;

/** RSS 피드 1개 — "이름|URL" 형식의 설정 문자열에서 만든다. */
public record RssFeed(String name, String url) {

    public static List<RssFeed> parseList(List<String> specs) {
        return specs.stream().map(RssFeed::parse).toList();
    }

    private static RssFeed parse(String spec) {
        int sep = spec.indexOf('|');
        if (sep <= 0 || sep == spec.length() - 1) {
            throw new IllegalStateException(
                    "NEWS_RSS_FEEDS 항목은 \"이름|URL\" 형식이어야 합니다: \"" + spec + "\"");
        }
        return new RssFeed(spec.substring(0, sep).trim(), spec.substring(sep + 1).trim());
    }
}
