package com.example.dart.filter;

import com.example.dart.filter.NewsFilter.TitleMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NewsFilterTest {

    private final NewsFilter filter = new NewsFilter();

    private void assertCategory(String expectedCategory, String reportNm) {
        Optional<TitleMatch> match = filter.matchTitle(reportNm);
        assertTrue(match.isPresent(), "매칭돼야 함: " + reportNm);
        assertEquals(expectedCategory, match.get().category(), "카테고리 불일치: " + reportNm);
    }

    private void assertRejected(String reportNm) {
        assertTrue(filter.matchTitle(reportNm).isEmpty(), "제외돼야 함: " + reportNm);
    }

    // ── Stage 1 : 제목 — 통과 ──────────────────────────────────────────

    @Test
    void 수주공급계약_매칭() {
        assertCategory("수주공급계약", "단일판매ㆍ공급계약체결");
        assertCategory("수주공급계약", "[첨부추가]단일판매 · 공급계약 체결");  // 접두어+공백+점 변형
        assertCategory("수주공급계약", "수주공시(자율공시)");
    }

    @Test
    void 주주환원_매칭() {
        assertCategory("주주환원", "주요사항보고서(자기주식취득결정)");
        assertCategory("주주환원", "주요사항보고서(주식소각결정)");
        assertCategory("주주환원", "주요사항보고서(현금ㆍ현물배당결정)");
        assertCategory("주주환원", "주요사항보고서(무상증자결정)");
        assertCategory("주주환원", "주요사항보고서(주식분할결정)");
    }

    @Test
    void 투자_특허_매칭() {
        assertCategory("투자", "신규시설투자등");
        assertCategory("특허", "특허권취득(자율공시)");
    }

    @Test
    void 기술계약_체결만_매칭() {
        assertCategory("기술계약", "투자판단관련주요경영사항(기술이전계약체결)");
        assertCategory("기술계약", "투자판단관련주요경영사항(기술수출 계약 체결)");
        assertRejected("투자판단관련주요경영사항(기술이전계약변경)");   // 변경 제외
        assertRejected("투자판단관련주요경영사항(기술이전계약 경과)"); // 체결 없음
    }

    @Test
    void 바이오승인_매칭() {
        assertCategory("바이오승인", "투자판단관련주요경영사항(임상시험계획승인)");
        assertCategory("바이오승인", "투자판단관련주요경영사항(품목허가승인)");
    }

    // ── Stage 1 : 제목 — 제외 ──────────────────────────────────────────

    @Test
    void 바이오_신청은_승인이_아니므로_제외() {
        assertRejected("투자판단관련주요경영사항(임상시험계획승인신청)");
        assertRejected("투자판단관련주요경영사항(품목허가신청)");
    }

    @Test
    void 정정_해지_철회_제외() {
        assertRejected("[기재정정]단일판매ㆍ공급계약체결");
        assertRejected("[첨부정정]단일판매ㆍ공급계약체결");
        assertRejected("단일판매ㆍ공급계약해지");
        assertRejected("주요사항보고서(자기주식취득신탁계약해지결정)");
        assertRejected("주요사항보고서(신규시설투자철회)");
    }

    @Test
    void 유무상증자_주식병합_제외() {
        assertRejected("주요사항보고서(유무상증자결정)");  // 희석 악재 — 무상증자결정 부분문자열 포함
        assertRejected("주요사항보고서(주식병합결정)");
    }

    @Test
    void 제거된_MnA_카테고리_제외() {
        assertRejected("주요사항보고서(회사합병결정)");
        assertRejected("주요사항보고서(영업양수도결정)");
        assertRejected("주요사항보고서(타법인주식및출자증권취득결정)");
        assertRejected("주요사항보고서(유형자산양수결정)");
    }

    @Test
    void 무관한_공시_및_빈값_제외() {
        assertRejected("사업보고서 (2025.12)");
        assertRejected("분기보고서 (2026.03)");
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
    }

    // ── Stage 2 : 본문 ─────────────────────────────────────────────────

    private static final TitleMatch CONTRACT_MATCH = new TitleMatch("수주공급계약", "단일판매");
    private static final TitleMatch BUYBACK_MATCH = new TitleMatch("주주환원", "자기주식취득");

    @Test
    void 조건부_본문_제외() {
        assertTrue(filter.bodyRejectReason("본 계약은 조건부 계약으로...", CONTRACT_MATCH).isPresent());
    }

    @Test
    void 매출액_비율_미달시_수주공급계약만_제외() {
        String body = "최근 매출액 대비(%) 5.2";
        assertTrue(filter.bodyRejectReason(body, CONTRACT_MATCH).isPresent());
        // 다른 카테고리의 본문 보일러플레이트에는 비율 검사를 적용하지 않음
        assertTrue(filter.bodyRejectReason(body, BUYBACK_MATCH).isEmpty());
    }

    @Test
    void 매출액_비율_충족시_통과() {
        assertTrue(filter.bodyRejectReason("매출액 대비 15.5%", CONTRACT_MATCH).isEmpty());
    }

    @Test
    void 본문_없으면_통과() {
        assertTrue(filter.bodyRejectReason(null, CONTRACT_MATCH).isEmpty());
        assertTrue(filter.bodyRejectReason("", CONTRACT_MATCH).isEmpty());
    }

    // ── 환경변수 확장 ───────────────────────────────────────────────────

    @Test
    void 추가_키워드는_사용자추가_카테고리로_매칭() {
        NewsFilter custom = new NewsFilter(List.of("합병결정"), List.of());
        assertEquals("사용자추가", custom.matchTitle("주요사항보고서(회사합병결정)").orElseThrow().category());
    }

    @Test
    void 추가_제외_키워드는_전역_차단() {
        NewsFilter custom = new NewsFilter(List.of(), List.of("자율공시"));
        assertTrue(custom.matchTitle("수주공시(자율공시)").isEmpty());
        assertTrue(custom.matchTitle("단일판매ㆍ공급계약체결").isPresent());
    }
}
