package com.example.dart.news;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 뉴스 제목을 호재/악재/시황으로 분류한다.
 * RSS·네이버 모두 이 분류를 통과한 기사만 알림 대상이 된다 (1차 필터 겸 태그).
 *
 * 분류 규칙:
 *  1. 악재 키워드 → 반전어가 함께 있으면 호재로 뒤집기 ("유상증자 철회", "거래정지 해제")
 *  2. 호재 키워드 → 반전어가 함께 있으면 악재로 뒤집기 ("수주 취소", "임상 3상 실패")
 *  3. 시황 단독 키워드 (FOMC, 서킷브레이커 등 — 그 자체로 뉴스인 것)
 *  4. 시황 주제어 + 충격어 조합 ("이란"+"공습", "유가"+"급등", "트럼프"+"관세")
 *     — 주제어(미국, 트럼프 등)는 일상 기사에도 흔하므로 단독으로는 알리지 않는다.
 *
 * 악재를 호재보다 먼저 검사한다 — 둘 다 걸리는 제목은 악재 신호가 우선이다.
 */
public class NewsKeywordClassifier {

    public static final String SENTIMENT_GOOD  = "호재";
    public static final String SENTIMENT_BAD   = "악재";
    public static final String SENTIMENT_MACRO = "시황";

    /** 분류 결과 — 어떤 성격의 어떤 키워드에 걸렸는지. 반전 시 키워드에 반전어를 병기한다. */
    public record Match(String sentiment, String keyword) {}

    /** 공백 제거 + 대문자화 — "금리 인상"이 "금리인상"으로 쓰인 제목에도 매칭되도록. */
    private static final Pattern NORMALIZE_PATTERN = Pattern.compile("\\s+");

    private final Map<String, String> goodKeywords;   // 정규화 키워드 → 원본
    private final Map<String, String> badKeywords;
    private final Map<String, String> macroKeywords;  // 단독으로 알림
    private final Map<String, String> macroTopics;    // 충격어와 조합 시에만 알림
    private final Map<String, String> macroTriggers;  // 충격어
    private final Map<String, String> flipKeywords;   // 호재↔악재 반전어

    public NewsKeywordClassifier(List<String> goodKeywords, List<String> badKeywords,
                                 List<String> macroKeywords, List<String> macroTopics,
                                 List<String> macroTriggers, List<String> flipKeywords) {
        this.goodKeywords  = normalizeAll(goodKeywords);
        this.badKeywords   = normalizeAll(badKeywords);
        this.macroKeywords = normalizeAll(macroKeywords);
        this.macroTopics   = normalizeAll(macroTopics);
        this.macroTriggers = normalizeAll(macroTriggers);
        this.flipKeywords  = normalizeAll(flipKeywords);
    }

    /** 제목 분류. 미매칭이면 empty — RSS 기사는 이때 버려진다. */
    public Optional<Match> classify(String title) {
        String normalized = normalize(title);

        Map.Entry<String, String> bad = findIn(badKeywords, normalized);
        if (bad != null) {
            String flip = flipIn(normalized, bad.getKey());
            return Optional.of(flip != null
                    ? new Match(SENTIMENT_GOOD, bad.getValue() + "·" + flip)
                    : new Match(SENTIMENT_BAD, bad.getValue()));
        }

        Map.Entry<String, String> good = findIn(goodKeywords, normalized);
        if (good != null) {
            String flip = flipIn(normalized, good.getKey());
            return Optional.of(flip != null
                    ? new Match(SENTIMENT_BAD, good.getValue() + "·" + flip)
                    : new Match(SENTIMENT_GOOD, good.getValue()));
        }

        Map.Entry<String, String> macro = findIn(macroKeywords, normalized);
        if (macro != null) {
            return Optional.of(new Match(SENTIMENT_MACRO, macro.getValue()));
        }

        Map.Entry<String, String> topic = findIn(macroTopics, normalized);
        if (topic != null) {
            Map.Entry<String, String> trigger = findIn(macroTriggers, normalized);
            if (trigger != null) {
                return Optional.of(new Match(SENTIMENT_MACRO, topic.getValue() + "·" + trigger.getValue()));
            }
        }

        return Optional.empty();
    }

    /**
     * 반전어 검색 — 매칭된 키워드 자신을 제목에서 지운 뒤 찾는다.
     * 안 그러면 "흑자전환"(호재)이 반전어 "흑자"에 걸려 스스로 뒤집힌다.
     */
    private String flipIn(String normalizedTitle, String matchedKeyword) {
        String remainder = normalizedTitle.replace(matchedKeyword, "");
        Map.Entry<String, String> flip = findIn(flipKeywords, remainder);
        return flip == null ? null : flip.getValue();
    }

    /** @return 제목에 포함된 첫 키워드 엔트리(정규화 → 원본). 없으면 null. */
    private static Map.Entry<String, String> findIn(Map<String, String> keywords, String normalizedTitle) {
        for (Map.Entry<String, String> kw : keywords.entrySet()) {
            if (normalizedTitle.contains(kw.getKey())) return kw;
        }
        return null;
    }

    private static String normalize(String s) {
        return NORMALIZE_PATTERN.matcher(s).replaceAll("").toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> normalizeAll(List<String> keywords) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String kw : keywords) {
            String normalized = normalize(kw);
            if (!normalized.isEmpty()) map.putIfAbsent(normalized, kw);
        }
        return map;
    }
}
