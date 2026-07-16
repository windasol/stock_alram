package com.example.dart.disclosure.domain;

import com.example.dart.disclosure.domain.NewsFilter.TitleMatch;
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
        assertCategory("수주공급계약", "우선협상대상자선정(자율공시)");  // 수주 직전 신호
    }

    @Test
    void 주주환원_매칭() {
        assertCategory("주주환원", "주요사항보고서(자기주식취득결정)");
        assertCategory("주주환원", "주요사항보고서(주식소각결정)");
        assertCategory("주주환원", "주요사항보고서(이익소각결정)");
    }

    @Test
    void 주주환원_저영향_공시_제외() {
        // 배당·무상증자·분할은 주가 영향이 미미해 제외(노이즈)
        assertRejected("주요사항보고서(현금ㆍ현물배당결정)");
        assertRejected("주요사항보고서(무상증자결정)");
        assertRejected("주요사항보고서(주식분할결정)");
    }

    @Test
    void 투자_특허_매칭() {
        assertCategory("투자", "신규시설투자등");
        assertCategory("투자", "신규시설투자등(생산라인증설)");
        assertCategory("특허", "특허권취득(자율공시)");
        assertCategory("특허", "특허 등록 결정(자율공시)");
    }

    @Test
    void 기술계약_매칭() {
        assertCategory("기술계약", "투자판단관련주요경영사항(기술이전계약체결)");
        assertCategory("기술계약", "투자판단관련주요경영사항(기술수출 계약 체결)");
        // 재현율 우선: 경과 공시(마일스톤 수령 등)도 호재 — "체결" 요구 제거
        assertCategory("기술계약", "투자판단관련주요경영사항(기술이전계약 경과)");
        assertRejected("투자판단관련주요경영사항(기술이전계약변경)");   // 변경(축소·지연)은 제외
    }

    @Test
    void 바이오승인_매칭() {
        assertCategory("바이오승인", "투자판단관련주요경영사항(임상시험계획승인)");
        assertCategory("바이오승인", "투자판단관련주요경영사항(품목허가승인)");
        assertCategory("바이오승인", "투자판단관련주요경영사항(임상시험결과)");
        assertCategory("바이오승인", "투자판단관련주요경영사항(미국 FDA 품목허가 승인)");
        assertCategory("바이오승인", "투자판단관련주요경영사항(희귀의약품지정)");
    }

    @Test
    void 장래계획_재개_승소_매칭() {
        assertCategory("장래계획", "장래사업ㆍ경영계획(공정공시)");
        assertCategory("영업재개", "생산재개(자율공시)");
        assertCategory("소송승소", "소송등의판결ㆍ결정 (특허침해소송 승소)");
        // 전역 제외("취하")는 그대로 적용
        assertRejected("소송등의판결ㆍ결정 (가처분 취하)");
        // 기타경영사항 전체 포함은 노이즈가 커서 의도적으로 미포함
        assertRejected("기타경영사항(자율공시)");
    }

    @Test
    void 안전망_주요경영사항_매칭() {
        // 특정 카테고리에 안 걸려도 "투자판단관련주요경영사항"이면 알림 (재현율 우선)
        assertCategory("주요경영사항", "투자판단관련주요경영사항(국책과제선정)");
        assertCategory("주요경영사항", "투자판단관련주요경영사항(해외자회사설립)");
        // "신청"은 바이오승인 룰에서 빠지지만 안전망이 받는다 — 알림은 나감
        assertCategory("주요경영사항", "투자판단관련주요경영사항(임상시험계획승인신청)");
        assertCategory("주요경영사항", "투자판단관련주요경영사항(품목허가신청)");
    }

    // ── Stage 1 : 제목 — 제외 ──────────────────────────────────────────

    @Test
    void 안전망_악재_키워드_제외() {
        assertRejected("투자판단관련주요경영사항(소송제기)");
        assertRejected("투자판단관련주요경영사항(횡령ㆍ배임혐의발생)");
        assertRejected("투자판단관련주요경영사항(생산중단)");  // 전역 "중단"
        assertRejected("투자판단관련주요경영사항(품목허가 불승인)");
    }

    @Test
    void 정정_공시는_전면_제외() {
        // 정정 공시는 호재 서식이어도 알림에서 제외한다.
        assertRejected("[기재정정]단일판매ㆍ공급계약체결");
        assertRejected("[첨부정정]단일판매ㆍ공급계약체결");
        assertCategory("수주공급계약", "단일판매ㆍ공급계약체결");  // 비정정은 통과
    }

    @Test
    void 해지_철회는_정정이어도_제외() {
        // 정정이어도 악재(해지·철회)면 여전히 차단된다.
        assertRejected("단일판매ㆍ공급계약해지");
        assertRejected("[기재정정]단일판매ㆍ공급계약해지");
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
    void 조건부계약_해당_명시만_제외() {
        assertTrue(filter.bodyRejectReason("2-1. 조건부 계약여부 해당 2-2. 조건내용 ...", CONTRACT_MATCH).isPresent());
        // 서식에 "조건부 계약여부" 항목이 항상 있으므로 "미해당"은 통과해야 함
        assertTrue(filter.bodyRejectReason("2-1. 조건부 계약여부 미해당", CONTRACT_MATCH).isEmpty());
        // 단어 "조건부"가 본문 어딘가에 있다는 이유만으로 제외하지 않음
        assertTrue(filter.bodyRejectReason("기타 조건부 사항 참고", CONTRACT_MATCH).isEmpty());
    }

    @Test
    void 매출액_비율_미달시_수주공급계약만_제외() {
        String body = "최근 매출액 대비(%) 3.2";
        assertTrue(filter.bodyRejectReason(body, CONTRACT_MATCH).isPresent());
        // 다른 카테고리의 본문 보일러플레이트에는 비율 검사를 적용하지 않음
        assertTrue(filter.bodyRejectReason(body, BUYBACK_MATCH).isEmpty());
    }

    @Test
    void 매출액_비율_충족시_통과() {
        assertTrue(filter.bodyRejectReason("매출액 대비 15.5%", CONTRACT_MATCH).isEmpty());
        assertTrue(filter.bodyRejectReason("매출액 대비(%) 8.5", CONTRACT_MATCH).isEmpty());     // 5% 이상
        assertTrue(filter.bodyRejectReason("매출액 대비(%) 1,031.5", CONTRACT_MATCH).isEmpty()); // 콤마 비율
    }

    @Test
    void 매출액_금액행이나_미기재는_비율로_오인하지_않음() {
        // "매출액(원)" 금액 행의 숫자를 비율로 읽으면 안 됨
        assertTrue(filter.bodyRejectReason("최근 매출액(원) 50,000,000,000", CONTRACT_MATCH).isEmpty());
        // 비율 미기재("-")는 통과
        assertTrue(filter.bodyRejectReason("최근 매출액(원) 50,000,000,000 매출액 대비(%) - 3. 계약기간", CONTRACT_MATCH).isEmpty());
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

    @Test
    void 자기주식취득_결정은_알리고_결과보고서는_제외() {
        assertCategory("주주환원", "자기주식취득결정");
        assertCategory("주주환원", "주요사항보고서(자기주식취득결정)");
        // 결과보고서는 사후 확인 공시 — 제외.
        assertRejected("자기주식취득결과보고서");
        assertRejected("자기주식 소각 결과보고서");
    }

    @Test
    void 자기주식취득결정만_취득금액_대상() {
        // 직접 취득결정 — 취득금액 덧붙임 대상.
        assertTrue(NewsFilter.isTreasuryAcquisition("주요사항보고서(자기주식취득결정)"));
        assertTrue(NewsFilter.isTreasuryAcquisition("자기주식 취득 결정"));
        // 신탁계약(계약금액 서식)·처분·해지는 제외.
        assertFalse(NewsFilter.isTreasuryAcquisition("자기주식취득 신탁계약 체결 결정"));
        assertFalse(NewsFilter.isTreasuryAcquisition("자기주식처분결정"));
        assertFalse(NewsFilter.isTreasuryAcquisition("자기주식취득 신탁계약 해지 결정"));
    }

    @Test
    void 자기주식_신탁계약_체결만_금액대상() {
        // 신탁계약 "체결"만 계약금액 덧붙임 대상.
        assertTrue(NewsFilter.isTreasuryTrustContract("자기주식취득 신탁계약 체결 결정"));
        assertTrue(NewsFilter.isTreasuryTrustContract("주요사항보고서(자기주식취득신탁계약체결결정)"));
        // 해지·취소·결과보고서는 제외.
        assertFalse(NewsFilter.isTreasuryTrustContract("자기주식취득 신탁계약 해지 결정"));
        assertFalse(NewsFilter.isTreasuryTrustContract("자기주식취득신탁계약해지결정"));
        assertFalse(NewsFilter.isTreasuryTrustContract("자기주식취득 신탁계약 해지 결과보고서"));
        // 직접취득결정(신탁 아님)은 대상 아님 — composeTreasury 경로가 담당.
        assertFalse(NewsFilter.isTreasuryTrustContract("주요사항보고서(자기주식취득결정)"));
    }

    @Test
    void 주식소각결정만_소각금액_대상() {
        // 소각 "결정"만 소각예정금액 덧붙임 대상.
        assertTrue(NewsFilter.isStockCancellation("주식소각결정"));
        assertTrue(NewsFilter.isStockCancellation("주요사항보고서(주식소각결정)"));
        assertTrue(NewsFilter.isStockCancellation("이익소각 결정"));
        assertTrue(NewsFilter.isStockCancellation("자기주식 소각 결정"));
        // 결과보고서(사후)·취소·철회는 제외.
        assertFalse(NewsFilter.isStockCancellation("주식소각결과보고서"));
        assertFalse(NewsFilter.isStockCancellation("주식소각결정 취소"));
        assertFalse(NewsFilter.isStockCancellation("주식소각결정 철회"));
    }
}
