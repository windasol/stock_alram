package com.example.dart.news;

import java.time.ZonedDateTime;

/**
 * 뉴스 기사 1건. 제목·설명은 HTML 태그·엔티티 제거 후 평문.
 *
 * @param source      출처 — RSS 피드 이름 또는 "네이버"
 * @param publishedAt 발행 시각. 파싱 실패 시 null (재현율 우선 — 나이를 알 수 없으면 통과)
 */
public record NewsArticle(
        String source,
        String title,
        String link,
        String originalLink,
        String description,
        ZonedDateTime publishedAt
) {}
