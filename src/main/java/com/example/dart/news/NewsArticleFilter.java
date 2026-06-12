package com.example.dart.news;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 뉴스 알림 필터. 키워드 분류(NewsKeywordClassifier)를 통과한 기사에서
 * 노이즈만 거른다 — 제외 키워드, 오래된 기사, 유사 제목 중복, 시황 키워드 쿨다운.
 *
 * 같은 이슈를 여러 매체가 거의 같은 제목으로 쏟아내므로, 최근 알림 보낸 제목과
 * 토큰 유사도가 높은 기사는 시간창 안에서 1회만 알린다.
 * 유사도는 오버랩 계수(교집합/작은쪽) — 자카드와 달리 조사 변형("미국"/"미국에서")이나
 * 꼬리말 추가("…후속 보도")로 한쪽 제목이 길어져도 중복으로 잡는다.
 *
 * 시황 뉴스는 같은 이슈의 새 전개("이란 공습 1보, 2보, 반응…")가 제목을 바꿔가며
 * 이어지므로, 유사도 필터에 더해 같은 키워드는 쿨다운 시간당 1회만 알린다.
 *
 * 단일 폴러 스레드에서만 호출되는 것을 전제로 한다 (동기화 없음).
 */
public class NewsArticleFilter {

    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(60);
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^가-힣a-z0-9]+");

    private record AlertedTitle(Set<String> tokens, Instant alertedAt) {}

    private final List<String> excludeKeywords;
    private final Duration maxAge;
    private final Duration macroCooldown;
    private final Deque<AlertedTitle> recentAlerts = new ArrayDeque<>();
    private final Map<String, Instant> lastMacroAlert = new HashMap<>();

    /**
     * @param excludeKeywords 제목에 포함되면 제외할 키워드 (대소문자 무시)
     * @param maxAge          이보다 오래된 기사는 제외 — 첫 실행 시 과거 기사 알림 폭주 방지
     * @param macroCooldown   시황 뉴스의 같은 키워드 재알림 최소 간격
     */
    public NewsArticleFilter(List<String> excludeKeywords, Duration maxAge, Duration macroCooldown) {
        this.excludeKeywords = excludeKeywords.stream()
                .map(k -> k.toLowerCase(Locale.ROOT))
                .toList();
        this.maxAge = maxAge;
        this.macroCooldown = macroCooldown;
    }

    /** @return 제외 사유. 알림 대상이면 empty. */
    public Optional<String> rejectReason(NewsArticle article, NewsKeywordClassifier.Match match, Instant now) {
        String title = article.title().toLowerCase(Locale.ROOT);
        for (String ex : excludeKeywords) {
            if (title.contains(ex)) return Optional.of("제외 키워드: " + ex);
        }

        if (article.publishedAt() != null
                && article.publishedAt().toInstant().isBefore(now.minus(maxAge))) {
            return Optional.of("오래된 기사 (" + maxAge.toMinutes() + "분 초과)");
        }

        if (NewsKeywordClassifier.SENTIMENT_MACRO.equals(match.sentiment())) {
            Instant last = lastMacroAlert.get(match.keyword());
            if (last != null && last.isAfter(now.minus(macroCooldown))) {
                return Optional.of("시황 쿨다운 (" + match.keyword() + ", " + macroCooldown.toMinutes() + "분)");
            }
        }

        prune(now);
        Set<String> tokens = tokenize(article.title());
        for (AlertedTitle alerted : recentAlerts) {
            if (overlap(tokens, alerted.tokens()) >= SIMILARITY_THRESHOLD) {
                return Optional.of("유사 기사 중복");
            }
        }
        return Optional.empty();
    }

    /** 알림 전송 직전에 호출 — 이후 같은 이슈의 타 매체 기사·후속 보도를 억제한다. */
    public void markAlerted(NewsArticle article, NewsKeywordClassifier.Match match, Instant now) {
        prune(now);
        recentAlerts.addLast(new AlertedTitle(tokenize(article.title()), now));
        if (NewsKeywordClassifier.SENTIMENT_MACRO.equals(match.sentiment())) {
            lastMacroAlert.put(match.keyword(), now);
        }
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(DEDUP_WINDOW);
        while (!recentAlerts.isEmpty() && recentAlerts.peekFirst().alertedAt().isBefore(cutoff)) {
            recentAlerts.removeFirst();
        }
    }

    /** 한글·영문·숫자 단어 토큰 (2자 이상 — 조사 등 1자 토큰은 노이즈). */
    private static Set<String> tokenize(String title) {
        Set<String> tokens = new HashSet<>();
        for (String t : TOKEN_SPLIT.split(title.toLowerCase(Locale.ROOT))) {
            if (t.length() >= 2) tokens.add(t);
        }
        return tokens;
    }

    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / Math.min(a.size(), b.size());
    }
}
