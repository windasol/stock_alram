package com.example.dart.news;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsArticleFilterTest {

    private static final Instant NOW = Instant.parse("2026-06-12T09:00:00Z");

    private static final NewsKeywordClassifier.Match GOOD_MATCH =
            new NewsKeywordClassifier.Match(NewsKeywordClassifier.SENTIMENT_GOOD, "수주");
    private static final NewsKeywordClassifier.Match MACRO_MATCH =
            new NewsKeywordClassifier.Match(NewsKeywordClassifier.SENTIMENT_MACRO, "유가·급등");

    private static NewsArticle article(String title, Instant publishedAt) {
        ZonedDateTime pub = publishedAt == null ? null : publishedAt.atZone(ZoneId.of("Asia/Seoul"));
        return new NewsArticle("테스트피드", title, "https://n.news.naver.com/" + title.hashCode(),
                "https://example.com/" + title.hashCode(), "요약", pub);
    }

    private static NewsArticleFilter filter(List<String> excludes, Duration maxAge) {
        return new NewsArticleFilter(excludes, maxAge, Duration.ofMinutes(10));
    }

    @Test
    void 제외_키워드가_제목에_있으면_거른다() {
        NewsArticleFilter filter = filter(List.of("코인"), Duration.ofMinutes(30));
        Optional<String> reject = filter.rejectReason(article("A사 코인 수주 발표", NOW), GOOD_MATCH, NOW);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("제외 키워드"));
    }

    @Test
    void 최대_나이를_넘긴_기사는_거른다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        Optional<String> reject = filter.rejectReason(
                article("B사 1조원 공급계약 체결", NOW.minus(Duration.ofMinutes(31))), GOOD_MATCH, NOW);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("오래된 기사"));
    }

    @Test
    void 발행시각이_없으면_나이검사_없이_통과한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        assertEquals(Optional.empty(),
                filter.rejectReason(article("C사 대규모 수주", null), GOOD_MATCH, NOW));
    }

    @Test
    void 알림_보낸_제목과_유사한_기사는_시간창_안에서_거른다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        NewsArticle first = article("삼성전자 미국에서 5조원 반도체 공급계약 체결", NOW);

        assertEquals(Optional.empty(), filter.rejectReason(first, GOOD_MATCH, NOW));
        filter.markAlerted(first, GOOD_MATCH, NOW);

        // 타 매체의 같은 이슈 — 어순·조사만 다른 제목
        Optional<String> reject = filter.rejectReason(
                article("삼성전자, 미국 5조원 규모 반도체 공급계약", NOW), GOOD_MATCH, NOW);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("유사 기사 중복"));
    }

    @Test
    void 다른_이슈의_기사는_통과한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        filter.markAlerted(article("삼성전자 미국에서 5조원 반도체 공급계약 체결", NOW), GOOD_MATCH, NOW);

        assertEquals(Optional.empty(), filter.rejectReason(
                article("한미약품 FDA 신약 품목허가 획득", NOW), GOOD_MATCH, NOW));
    }

    @Test
    void 조사_어미만_다른_교차_보도를_중복으로_잡는다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        filter.markAlerted(
                article("삼성전자가 미국에서 5조원 규모 반도체를 수주했다", NOW), GOOD_MATCH, NOW);

        // 조사·숫자표기만 다른 타 매체 보도 — 정규화 전이라면 토큰이 어긋나 놓쳤을 케이스
        Optional<String> reject = filter.rejectReason(
                article("삼성전자, 미국 5조 반도체 공급계약 체결", NOW), GOOD_MATCH, NOW);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("유사 기사 중복"));
    }

    @Test
    void 말머리_장식이_달라도_중복으로_잡는다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        filter.markAlerted(article("[속보] LG엔솔 2조 공급계약", NOW), GOOD_MATCH, NOW);

        Optional<String> reject = filter.rejectReason(
                article("LG엔솔 2조원 규모 배터리 공급계약 체결", NOW), GOOD_MATCH, NOW);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("유사 기사 중복"));
    }

    @Test
    void 같은_회사라도_다른_사건이면_통과한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        filter.markAlerted(article("삼성전자 1분기 영업이익 흑자전환", NOW), GOOD_MATCH, NOW);

        // 회사명만 겹치므로 최소 공유 토큰 가드에 막혀 중복이 아니어야 한다 (과잉억제 방지)
        assertEquals(Optional.empty(), filter.rejectReason(
                article("삼성전자 미국 반도체 공장 착공", NOW), GOOD_MATCH, NOW));
    }

    @Test
    void 다른_회사의_같은_유형_계약은_통과한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofMinutes(30));
        filter.markAlerted(article("A사 1조 공급계약 체결", NOW), GOOD_MATCH, NOW);

        // "공급계약·체결"만 겹쳐도 회사·규모가 다르면 별개 딜 — 묶지 않는다
        assertEquals(Optional.empty(), filter.rejectReason(
                article("B사 2조 공급계약 체결", NOW), GOOD_MATCH, NOW));
    }

    @Test
    void 중복_억제_시간창이_지나면_다시_통과한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofDays(2));
        filter.markAlerted(article("삼성전자 미국에서 5조원 반도체 공급계약 체결", NOW), GOOD_MATCH, NOW);

        Instant later = NOW.plus(Duration.ofHours(24).plusMinutes(1));  // 중복 억제 창(24시간) 경과
        assertEquals(Optional.empty(), filter.rejectReason(
                article("삼성전자 미국 5조원 반도체 공급계약 후속 보도", later), GOOD_MATCH, later));
    }

    @Test
    void 시간창_안의_같은_기사는_한시간_뒤에도_억제한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofDays(2));
        filter.markAlerted(article("삼성전자 미국에서 5조원 반도체 공급계약 체결", NOW), GOOD_MATCH, NOW);

        // 예전 60분 창에선 통과하던 케이스 — 24시간 창에선 다른 플랫폼의 같은 기사를 계속 억제
        Instant later = NOW.plus(Duration.ofMinutes(61));
        Optional<String> reject = filter.rejectReason(
                article("삼성전자, 미국 5조원 규모 반도체 공급계약", later), GOOD_MATCH, later);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("유사 기사 중복"));
    }

    @Test
    void 재시작해도_영속파일에서_제목기억을_복원해_중복을_막는다(@TempDir Path dir) {
        Path titles = dir.resolve("seen_news_titles.txt");
        Instant now = Instant.now();  // load()가 실제 현재시각 기준 24시간 창으로 거르므로 fresh해야 함

        NewsArticleFilter first = new NewsArticleFilter(
                List.of(), Duration.ofHours(1), Duration.ofMinutes(10), titles);
        first.markAlerted(article("삼성전자 미국에서 5조원 반도체 공급계약 체결", now), GOOD_MATCH, now);

        // 재시작 시뮬레이션 — 메모리는 비었지만 같은 파일을 가리키는 새 인스턴스
        NewsArticleFilter restarted = new NewsArticleFilter(
                List.of(), Duration.ofHours(1), Duration.ofMinutes(10), titles);
        Optional<String> reject = restarted.rejectReason(
                article("삼성전자, 미국 5조원 규모 반도체 공급계약", now), GOOD_MATCH, now);
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("유사 기사 중복"));
    }

    // ── 시황 쿨다운 ─────────────────────────────────────────────────────

    @Test
    void 같은_시황_키워드는_쿨다운_안에서_거른다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofHours(24));
        filter.markAlerted(article("국제유가 5% 급등", NOW), MACRO_MATCH, NOW);

        // 제목은 전혀 달라도 같은 키워드(유가·급등)면 쿨다운(10분) 적용
        Optional<String> reject = filter.rejectReason(
                article("WTI 배럴당 95달러 돌파, 항공주 직격탄", NOW.plus(Duration.ofMinutes(5))),
                MACRO_MATCH, NOW.plus(Duration.ofMinutes(5)));
        assertTrue(reject.isPresent());
        assertTrue(reject.get().contains("시황 쿨다운"));
    }

    @Test
    void 시황_쿨다운이_지나면_다시_통과한다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofHours(24));
        filter.markAlerted(article("국제유가 5% 급등", NOW), MACRO_MATCH, NOW);

        Instant later = NOW.plus(Duration.ofMinutes(11));
        assertEquals(Optional.empty(), filter.rejectReason(
                article("WTI 배럴당 95달러 돌파, 항공주 직격탄", later), MACRO_MATCH, later));
    }

    @Test
    void 시황_쿨다운은_호재_악재에는_적용되지_않는다() {
        NewsArticleFilter filter = filter(List.of(), Duration.ofHours(24));
        filter.markAlerted(article("A사 수주 공시", NOW), GOOD_MATCH, NOW);

        // 같은 키워드(수주)지만 제목이 다른 호재 — 쿨다운 없이 통과
        assertEquals(Optional.empty(), filter.rejectReason(
                article("B중공업 해외 플랜트 계약 체결", NOW.plus(Duration.ofMinutes(1))),
                GOOD_MATCH, NOW.plus(Duration.ofMinutes(1))));
    }
}
