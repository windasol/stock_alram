package com.example.dart.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 뉴스 알림 필터. 속보로 판정된 기사에서 노이즈만 거른다 — 제외 키워드, 오래된 기사, 유사 제목 중복.
 *
 * 같은 이슈를 여러 매체가 거의 같은 제목으로 쏟아내므로, 최근 알림 보낸 제목과
 * 토큰 유사도가 높은 기사는 시간창(24시간) 안에서 1회만 알린다. 같은 기사가 여러
 * 플랫폼에서 다른 시각대로 반복 유입되는데, 플랫폼이 다르면 URL이 달라 SeenStore가
 * 못 잡으므로 제목 유사도가 유일한 신호다 — 종일 반복을 막으려면 창이 하루는 돼야 한다.
 * 비교 전 토큰을 정규화한다 — 장식([속보]·(종합2보)·따옴표) 제거, 조사 절단
 * ("미국에서"→"미국", "삼성전자가"→"삼성전자"), 숫자 표기 통일("5조원"→"5조") —
 * 한국어 조사·표기 변형으로 같은 사건을 놓치던 문제를 줄인다.
 * 유사도는 오버랩 계수(교집합/작은쪽)에 최소 공유 토큰 수 가드를 더해, 한쪽 제목이
 * 길어져도 중복을 잡되 짧은 제목이 회사명만 겹쳐 오병합되는 것은 막는다.
 *
 * 알림 보낸 제목 기억은 파일로 영속화한다. 소스 수정·배포로 재시작이 잦은데, 메모리에만
 * 두면 재시작 때마다 기억이 비워져 하루 종일 피드에 남는 같은 기사를 재시작 직후마다
 * 다시 알리기 때문이다 (같은 이슈를 매체별로 다른 URL로 받아 SeenStore로는 못 막는다).
 *
 * 시황 등 키워드 분류는 제거됐다 — 알림 게이트는 속보 말머리(BreakingNews)뿐이다.
 *
 * 단일 폴러 스레드에서만 호출되는 것을 전제로 한다 (동기화 없음).
 */
public class NewsArticleFilter {

    private static final Logger log = LoggerFactory.getLogger(NewsArticleFilter.class);

    private static final double SIMILARITY_THRESHOLD = 0.6;
    /** 같은 사건으로 보려면 의미 토큰이 최소 이만큼 겹쳐야 한다 — 짧은 제목의 회사명·일반어 오병합 방지. */
    private static final int MIN_SHARED_TOKENS = 3;
    /** 같은 이슈를 하루 1회만 알린다 — 종일 다른 시각대로 재유입되는 동일 기사 억제. */
    private static final Duration DEDUP_WINDOW = Duration.ofHours(24);
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^가-힣a-z0-9]+");

    /** 제목 장식 — 언론사 말머리·보도 차수·따옴표·말줄임. 토큰을 흔들어 중복을 놓치게 하므로 비교 전에 제거. */
    private static final Pattern DECORATION = Pattern.compile(
            "\\[[^\\]]*\\]"                       // [속보] [단독] [종합]
            + "|\\((?:종합|속보)[0-9]*보?\\)"      // (종합) (종합2보) (속보)
            + "|\\([0-9]+보\\)"                    // (2보)
            + "|[\"'‘’“”]"    // 따옴표류
            + "|\\.{2,}|…");                  // ... …

    /** 자릿수 구분 콤마 (50,000 → 50000) — 토큰 분리 전에 제거해야 숫자가 쪼개지지 않는다. */
    private static final Pattern DIGIT_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");

    /**
     * 토큰 끝 조사 — 긴 것부터 시도(에서/으로…)한 뒤 1글자. 한국어 조사 변형으로
     * "삼성전자가"≠"삼성전자"가 되어 중복을 놓치는 문제를 줄인다.
     */
    private static final String[] JOSA = {
            "에서", "으로", "까지", "부터", "에게", "한테",
            "이", "가", "은", "는", "을", "를", "의", "에", "로", "와", "과", "도", "만", "께"
    };

    /** 영속 파일이 base + 이만큼 늘어나면 만료분을 떨궈 다시 쓴다 (장기 실행 시 무한 증가 방지). */
    private static final int COMPACT_EVERY = 500;

    private record AlertedTitle(Set<String> tokens, Instant alertedAt) {}

    private final List<String> excludeKeywords;
    private final Duration maxAge;
    private final Deque<AlertedTitle> recentAlerts = new ArrayDeque<>();

    /** 알림 보낸 제목 토큰 영속 파일. null이면 영속화 없이 메모리로만 동작(테스트용). */
    private final Path titlesFile;
    private int appendsSinceCompact = 0;

    /**
     * @param excludeKeywords 제목에 포함되면 제외할 키워드 (대소문자 무시)
     * @param maxAge          이보다 오래된 기사는 제외 — 첫 실행 시 과거 기사 알림 폭주 방지
     */
    public NewsArticleFilter(List<String> excludeKeywords, Duration maxAge) {
        this(excludeKeywords, maxAge, null);
    }

    /**
     * @param titlesFile 알림 보낸 제목 기억을 영속화할 파일 (재시작 시 중복 억제 유지). null이면 메모리만.
     */
    public NewsArticleFilter(List<String> excludeKeywords, Duration maxAge, Path titlesFile) {
        this.excludeKeywords = excludeKeywords.stream()
                .map(k -> k.toLowerCase(Locale.ROOT))
                .toList();
        this.maxAge = maxAge;
        this.titlesFile = titlesFile;
        if (titlesFile != null) load();
    }

    /** @return 제외 사유. 알림 대상이면 empty. */
    public Optional<String> rejectReason(NewsArticle article, Instant now) {
        String title = article.title().toLowerCase(Locale.ROOT);
        for (String ex : excludeKeywords) {
            if (title.contains(ex)) return Optional.of("제외 키워드: " + ex);
        }

        if (article.publishedAt() != null
                && article.publishedAt().toInstant().isBefore(now.minus(maxAge))) {
            return Optional.of("오래된 기사 (" + maxAge.toMinutes() + "분 초과)");
        }

        prune(now);
        Set<String> tokens = tokenize(article.title());
        for (AlertedTitle alerted : recentAlerts) {
            if (isDuplicate(tokens, alerted.tokens())) {
                return Optional.of("유사 기사 중복");
            }
        }
        return Optional.empty();
    }

    /** 알림 전송 직전에 호출 — 이후 같은 이슈의 타 매체 기사·후속 보도를 억제한다. */
    public void markAlerted(NewsArticle article, Instant now) {
        prune(now);
        Set<String> tokens = tokenize(article.title());
        recentAlerts.addLast(new AlertedTitle(tokens, now));
        appendTitle(now, tokens);
    }

    /**
     * 시작 시 영속 파일에서 시간창(24시간) 안의 제목 기억을 복원한다 — 재시작해도 이미 알린
     * 이슈를 잊지 않게 한다. 파일은 시간순(append 순)이라 그대로 읽으면 큐 정렬이 유지된다.
     * 만료·손상 줄은 버리고, 끝나면 압축해 다시 써 파일을 정리한다(기존 만료분 제거).
     */
    private void load() {
        if (!Files.exists(titlesFile)) return;
        Instant now = Instant.now();
        Instant cutoff = now.minus(DEDUP_WINDOW);
        int restored = 0;
        try {
            for (String line : Files.readAllLines(titlesFile)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                Instant at;
                try {
                    at = Instant.ofEpochMilli(Long.parseLong(line.substring(0, tab)));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (at.isBefore(cutoff)) continue;
                Set<String> tokens = new HashSet<>();
                for (String t : line.substring(tab + 1).split(" ")) {
                    if (!t.isEmpty()) tokens.add(t);
                }
                if (tokens.isEmpty()) continue;
                recentAlerts.addLast(new AlertedTitle(tokens, at));
                restored++;
            }
            rewrite(now);
            log.info("뉴스 제목 중복 기억 {}건 복원 완료 ({})", restored, titlesFile);
        } catch (IOException e) {
            log.warn("{} 로드 실패, 빈 상태로 시작", titlesFile, e);
        }
    }

    /** 토큰 집합을 "epochMillis\t토큰들" 한 줄로 append. 일정량 쌓이면 압축한다. */
    private void appendTitle(Instant at, Set<String> tokens) {
        if (titlesFile == null || tokens.isEmpty()) return;
        try {
            Files.writeString(titlesFile, at.toEpochMilli() + "\t" + String.join(" ", tokens) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (++appendsSinceCompact >= COMPACT_EVERY) rewrite(at);
        } catch (IOException e) {
            log.warn("{} 기록 실패", titlesFile, e);
        }
    }

    /** 현재(만료 제거 후) 기억을 파일에 통째로 다시 써 무한 증가를 막는다. */
    private void rewrite(Instant now) {
        if (titlesFile == null) return;
        prune(now);
        StringBuilder sb = new StringBuilder();
        for (AlertedTitle a : recentAlerts) {
            sb.append(a.alertedAt().toEpochMilli()).append('\t')
              .append(String.join(" ", a.tokens())).append(System.lineSeparator());
        }
        try {
            Files.writeString(titlesFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            appendsSinceCompact = 0;
        } catch (IOException e) {
            log.warn("{} 압축 기록 실패", titlesFile, e);
        }
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(DEDUP_WINDOW);
        while (!recentAlerts.isEmpty() && recentAlerts.peekFirst().alertedAt().isBefore(cutoff)) {
            recentAlerts.removeFirst();
        }
    }

    /**
     * 비교용 토큰 집합 (2자 이상). 장식 제거 → 콤마 정리 → 분리 → 토큰별 정규화 순.
     * 정규화(조사 절단·숫자 표기 통일)는 같은 사건의 다른 표현을 같은 토큰으로 모아
     * 교차 언론사 중복 탐지율을 높인다 — 이 토큰은 중복판정에만 쓰이고 분류·표시엔 영향 없다.
     */
    private static Set<String> tokenize(String title) {
        String cleaned = DECORATION.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ");
        cleaned = DIGIT_COMMA.matcher(cleaned).replaceAll("");
        Set<String> tokens = new HashSet<>();
        for (String raw : TOKEN_SPLIT.split(cleaned)) {
            if (raw.isEmpty()) continue;
            String t = normalizeToken(raw);
            if (t.length() >= 2) tokens.add(t);
        }
        return tokens;
    }

    /** 숫자 뒤 '원' 절단(5조원→5조, 병원·원자력 등은 보존) 후 조사 절단. */
    private static String normalizeToken(String t) {
        if (t.length() >= 3 && t.endsWith("원")) {
            char before = t.charAt(t.length() - 2);
            if (Character.isDigit(before) || "조억만천".indexOf(before) >= 0) {
                t = t.substring(0, t.length() - 1);
            }
        }
        return stripJosa(t);
    }

    /** 끝의 조사를 1회 절단하되, 남는 어간이 2자 이상일 때만 (과절단 방지: 국가→국 금지). */
    private static String stripJosa(String t) {
        for (String j : JOSA) {
            if (t.length() > j.length() && t.endsWith(j)) {
                String stem = t.substring(0, t.length() - j.length());
                return stem.length() >= 2 ? stem : t;
            }
        }
        return t;
    }

    /**
     * 두 제목이 같은 사건인가 — 의미 토큰을 {@value #MIN_SHARED_TOKENS}개 이상 공유하고,
     * 겹침 계수(교집합/작은쪽)가 임계 이상일 때만. 두 조건을 모두 요구해 짧은 제목 오병합을 막는다.
     */
    private static boolean isDuplicate(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int shared = 0;
        for (String t : smaller) {
            if (larger.contains(t)) shared++;
        }
        if (shared < MIN_SHARED_TOKENS) return false;
        return (double) shared / smaller.size() >= SIMILARITY_THRESHOLD;
    }
}
