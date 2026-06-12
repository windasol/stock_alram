package com.example.dart.news;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 뉴스 기사 1건을 채널 공통 마크다운 알림 메시지로 조립한다.
 * 첫 줄에 신호등 이모지 + 키워드 + 제목 — 스크롤하며 훑어보기 좋게.
 */
public class NewsAlertComposer {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public String compose(NewsArticle article, NewsKeywordClassifier.Match match) {
        StringBuilder sb = new StringBuilder();
        sb.append(emoji(match.sentiment()))
                .append(" **").append(match.keyword()).append("** | ")
                .append(article.title()).append('\n');
        sb.append(article.source());
        if (article.publishedAt() != null) {
            sb.append(" · ").append(TIME_FMT.format(article.publishedAt()));
        }
        sb.append('\n').append(primaryLink(article));
        if (!article.description().isBlank()) {
            sb.append("\n\n").append(article.description());
        }
        return sb.toString();
    }

    private static String emoji(String sentiment) {
        return switch (sentiment) {
            case NewsKeywordClassifier.SENTIMENT_GOOD  -> "🟢";
            case NewsKeywordClassifier.SENTIMENT_BAD   -> "🔴";
            case NewsKeywordClassifier.SENTIMENT_MACRO -> "🌐";
            default -> "⚪";
        };
    }

    /** 언론사 원문 링크 우선, 없으면 네이버 링크. */
    private static String primaryLink(NewsArticle article) {
        String original = article.originalLink();
        return (original != null && !original.isBlank()) ? original : article.link();
    }
}
