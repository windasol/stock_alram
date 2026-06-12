package com.example.dart.news;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsKeywordClassifierTest {

    private final NewsKeywordClassifier classifier = new NewsKeywordClassifier(
            List.of("수주", "공급계약", "FDA 승인", "임상 3상", "흑자전환"),   // 호재
            List.of("거래정지", "유상증자", "상장폐지", "횡령", "소송", "적자"), // 악재
            List.of("FOMC", "서킷브레이커"),                                  // 시황 단독
            List.of("유가", "미국", "이란", "트럼프"),                        // 시황 주제어
            List.of("급등", "급락", "공습", "제재", "관세", "전쟁"),           // 시황 충격어
            List.of("철회", "취소", "실패", "해제", "무혐의", "승소", "흑자")); // 반전어

    // ── 호재/악재 기본 분류 ──────────────────────────────────────────────

    @Test
    void 호재_키워드_매칭() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("A사, 5조원 규모 수주 성공");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
        assertEquals("수주", match.get().keyword());
    }

    @Test
    void 악재_키워드_매칭() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("B사 주식 거래정지");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_BAD, match.get().sentiment());
    }

    @Test
    void 호재와_악재가_같이_있으면_악재_우선() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("수주 호재에도 횡령 혐의 적발");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_BAD, match.get().sentiment());
    }

    @Test
    void 영문_키워드는_대소문자_무시() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("D사 fda 승인 획득");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
    }

    // ── 반전어 — 문맥 뒤집기 ────────────────────────────────────────────

    @Test
    void 악재_키워드에_반전어가_붙으면_호재() {
        // 유상증자(악재) + 철회(반전) = 호재
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("C사, 유상증자 결정 철회");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
        assertEquals("유상증자·철회", match.get().keyword());
    }

    @Test
    void 거래정지_해제는_호재() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("D사 거래정지 해제, 거래 재개");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
    }

    @Test
    void 호재_키워드에_반전어가_붙으면_악재() {
        // 수주(호재) + 취소(반전) = 악재
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("E사 1조원 수주 계약 취소 통보");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_BAD, match.get().sentiment());
        assertEquals("수주·취소", match.get().keyword());
    }

    @Test
    void 임상_실패는_악재() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("F사 임상 3상 실패 발표");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_BAD, match.get().sentiment());
    }

    @Test
    void 횡령_무혐의는_호재() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("G사 대표 횡령 혐의 무혐의 처분");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
    }

    @Test
    void 소송_승소는_호재() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("H사, 미국 특허소송 승소");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
        assertEquals("소송·승소", match.get().keyword());
    }

    @Test
    void 키워드_자신은_반전어로_안_잡힌다() {
        // "흑자전환"(호재)이 반전어 "흑자"에 스스로 뒤집히면 안 된다
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("I사 3분기 흑자전환 성공");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
        assertEquals("흑자전환", match.get().keyword());
    }

    @Test
    void 적자와_흑자가_같이_있으면_호재로_반전() {
        // "적자"(악재) + 별도의 "흑자" 언급 = 흑자 전환 맥락
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("J사, 5년 적자 끊고 흑자 달성");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_GOOD, match.get().sentiment());
    }

    // ── 시황 — 단독 키워드와 주제어+충격어 조합 ──────────────────────────

    @Test
    void 시황_단독_키워드는_바로_매칭() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("FOMC 결과 발표 임박");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_MACRO, match.get().sentiment());
    }

    @Test
    void 주제어와_충격어가_같이_있으면_시황() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("이란 핵시설 공습에 국제 유가 출렁");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_MACRO, match.get().sentiment());
        assertEquals("유가·공습", match.get().keyword());
    }

    @Test
    void 트럼프_관세는_시황() {
        Optional<NewsKeywordClassifier.Match> match = classifier.classify("트럼프, 중국산 전 품목 관세 인상 발표");
        assertTrue(match.isPresent());
        assertEquals(NewsKeywordClassifier.SENTIMENT_MACRO, match.get().sentiment());
    }

    @Test
    void 주제어만_있으면_미매칭() {
        // "트럼프" 단독 — 일상 기사라 알리지 않음
        assertEquals(Optional.empty(), classifier.classify("트럼프 대통령, 신임 보좌관 임명"));
    }

    @Test
    void 충격어만_있으면_미매칭() {
        assertEquals(Optional.empty(), classifier.classify("동네 마트 물가 급등"));
    }

    @Test
    void 미매칭이면_empty() {
        assertEquals(Optional.empty(), classifier.classify("E사 신제품 출시 행사 개최"));
    }
}
