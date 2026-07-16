package com.example.dart.disclosure.application;

import com.example.dart.disclosure.infra.DartClient;
import com.example.dart.disclosure.infra.DocumentNotReadyException;
import com.example.dart.disclosure.domain.NewsFilter;
import com.example.dart.disclosure.infra.KindClient;
import com.example.dart.disclosure.infra.KindDocumentClient;
import com.example.dart.disclosure.domain.Disclosure;
import com.example.dart.disclosure.infra.DocumentParser;
import com.example.dart.common.infra.StockQuoteClient;
import com.example.dart.disclosure.application.DocumentService;
import com.example.dart.trade.TradeSignalListener;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AlertComposer 특성화(스냅샷) 테스트 — 메시지 출력 문자열을 고정 입력으로 못박는다.
 *
 * 리팩토링(조립 로직 분리·Treasury 3중복 통합) 전후로 알림 문자열이 바이트 단위로
 * 동일함을 보장하는 안전망. 기대값은 현재 구현의 실제 출력을 그대로 기록한 것이다.
 */
class AlertComposerTest {

    private static final Disclosure CONTRACT = new Disclosure(
            "테스트전자", "00123456", "123456", "Y",
            "단일판매ㆍ공급계약체결", "20260715000001", "20260715", "테스트전자");

    private static final Disclosure TREASURY = new Disclosure(
            "테스트전자", "00123456", "123456", "Y",
            "주요사항보고서(자기주식취득결정)", "20260715000002", "20260715", "테스트전자");

    // ── 스텁: 네트워크 의존성을 고정값으로 대체 ─────────────────────────

    private static class StubQuote extends StockQuoteClient {
        final OptionalLong cap;
        StubQuote(OptionalLong cap) { this.cap = cap; }
        @Override public OptionalLong marketCapWon(String stockCode) { return cap; }
    }

    private static class StubDart extends DartClient {
        final OptionalLong revenue;
        StubDart(OptionalLong revenue) { super("test-key"); this.revenue = revenue; }
        @Override public OptionalLong recentRevenueWon(String corpCode) { return revenue; }
    }

    private static class StubDocs extends DocumentService {
        DocumentParser.ContractInfo contract;
        String plainText;
        RuntimeException failure;
        StubDocs() { super(new StubDart(OptionalLong.empty()), new DocumentParser()); }
        @Override public DocumentParser.ContractInfo contractInfo(String rceptNo) {
            if (failure != null) throw failure;
            return contract;
        }
        @Override public String toPlainText(String rceptNo) {
            if (failure != null) throw failure;
            return plainText;
        }
    }

    private static class StubKind extends KindClient {
        final Optional<String> acptNo;
        StubKind(String acptNo) { this.acptNo = Optional.ofNullable(acptNo); }
        @Override public Optional<String> findAcptNo(String date, String company, String title) { return acptNo; }
    }

    private static class StubKindDoc extends KindDocumentClient {
        final String bodyHtml;
        final String stockCode;
        StubKindDoc(String bodyHtml, String stockCode) { this.bodyHtml = bodyHtml; this.stockCode = stockCode; }
        @Override public KindDocument fetch(String acptNo) {
            return new KindDocument(bodyHtml.getBytes(StandardCharsets.UTF_8), stockCode);
        }
    }

    /** 신호 값을 기록만 하는 자동매매 리스너. */
    private static class RecordingListener implements TradeSignalListener {
        String signalId; String corpName; String stockCode;
        long contractWon; OptionalLong revenueWon; double salesRatioPct;
        int calls;
        @Override public void onContractSignal(String signalId, String corpName, String stockCode,
                                               long contractWon, OptionalLong revenueWon, double salesRatioPct) {
            this.signalId = signalId; this.corpName = corpName; this.stockCode = stockCode;
            this.contractWon = contractWon; this.revenueWon = revenueWon; this.salesRatioPct = salesRatioPct;
            this.calls++;
        }
    }

    /** KIND 없이(DART 경로만) 구성한 컴포저 — 시총 2,000억, DART 매출 405억 기본. */
    private static AlertComposer dartOnlyComposer(StubDocs docs) {
        return new AlertComposer(docs, new NewsFilter(),
                new StubQuote(OptionalLong.of(200_000_000_000L)),
                new StubDart(OptionalLong.of(40_500_000_000L)),
                null, null, new DocumentParser());
    }

    // ── composeHeader ───────────────────────────────────────────────

    @Test
    void 헤더는_감지시각만_변동하고_나머지는_고정_포맷이다() {
        AlertComposer composer = dartOnlyComposer(new StubDocs());
        String msg = composer.composeHeader(CONTRACT, new NewsFilter.TitleMatch("수주·계약", "공급계약"));
        String normalized = msg.replaceFirst("감지 \\d{2}:\\d{2}:\\d{2}", "감지 <TIME>");
        assertEquals("""
                📋 **수주·계약 · 코스피** | 테스트전자 — 단일판매ㆍ공급계약체결
                접수 2026-07-15 · 감지 <TIME> · 제출인 테스트전자
                https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260715000001""", normalized);
    }

    @Test
    void 정정_공시_헤더는_회전_이모지와_정정_태그를_붙인다() {
        Disclosure correction = new Disclosure(
                "테스트전자", "00123456", "123456", "Y",
                "[기재정정]단일판매ㆍ공급계약체결", "20260715000003", "20260715", "테스트전자");
        AlertComposer composer = dartOnlyComposer(new StubDocs());
        String msg = composer.composeHeader(correction, new NewsFilter.TitleMatch("수주·계약", "공급계약"));
        String normalized = msg.replaceFirst("감지 \\d{2}:\\d{2}:\\d{2}", "감지 <TIME>");
        assertEquals("""
                🔁 **[정정] 수주·계약 · 코스피** | 테스트전자 — [기재정정]단일판매ㆍ공급계약체결
                접수 2026-07-15 · 감지 <TIME> · 제출인 테스트전자
                https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260715000003""", normalized);
    }

    // ── composeFollowup ─────────────────────────────────────────────

    @Test
    void 후속_메시지는_계약금액_매출대비_시총대비_핵심정보를_모두_담는다() {
        StubDocs docs = new StubDocs();
        docs.contract = new DocumentParser.ContractInfo(
                OptionalLong.of(50_000_000_000L), 12.3, OptionalLong.of(40_500_000_000L),
                "ABC상사", "2025-01-01 ~ 2026-12-31");
        String msg = dartOnlyComposer(docs).composeFollowup(CONTRACT);
        assertEquals("""
                📊 **시총·매출 대비** | 테스트전자 — 단일판매ㆍ공급계약체결
                💰 계약금액 500억 · 매출 대비 12.3% · 시총 대비 25.0%
                📈 시총 2,000억 · 매출액 405억 · 계약상대방 ABC상사 · 계약기간 2025-01-01 ~ 2026-12-31""", msg);
    }

    @Test
    void 명시_비율이_없으면_계약금액을_DART_매출로_나눠_계산한다() {
        StubDocs docs = new StubDocs();
        docs.contract = new DocumentParser.ContractInfo(
                OptionalLong.of(50_000_000_000L), null, OptionalLong.empty(), null, null);
        AlertComposer composer = new AlertComposer(docs, new NewsFilter(),
                new StubQuote(OptionalLong.of(200_000_000_000L)),
                new StubDart(OptionalLong.of(250_000_000_000L)),
                null, null, new DocumentParser());
        String msg = composer.composeFollowup(CONTRACT);
        assertEquals("""
                📊 **시총·매출 대비** | 테스트전자 — 단일판매ㆍ공급계약체결
                💰 계약금액 500억 · 매출 대비 20.0% · 시총 대비 25.0%
                📈 시총 2,000억 · 매출액 2,500억""", msg);
    }

    @Test
    void 상세_조회가_실패하면_실패_안내_한_줄로_폴백한다() {
        StubDocs docs = new StubDocs();
        docs.failure = new RuntimeException("boom");
        String msg = dartOnlyComposer(docs).composeFollowup(CONTRACT);
        assertEquals("📊 **시총·매출 대비** | 테스트전자 — 단일판매ㆍ공급계약체결\n(상세 내역 조회 실패)", msg);
    }

    @Test
    void 원문_미공개_014는_삼키지_않고_전파해_재조회를_트리거한다() {
        StubDocs docs = new StubDocs();
        docs.failure = new DocumentNotReadyException("014");
        AlertComposer composer = dartOnlyComposer(docs);
        assertThrows(DocumentNotReadyException.class, () -> composer.composeFollowup(CONTRACT));
    }

    // ── 자동매매 신호 ────────────────────────────────────────────────

    @Test
    void 계약_규모_확정_시_자동매매_리스너에_원시값으로_신호를_넘긴다() {
        StubDocs docs = new StubDocs();
        docs.contract = new DocumentParser.ContractInfo(
                OptionalLong.of(50_000_000_000L), 12.3, OptionalLong.of(40_500_000_000L), null, null);
        AlertComposer composer = dartOnlyComposer(docs);
        RecordingListener listener = new RecordingListener();
        composer.setTradeSignalListener(listener);
        composer.composeFollowup(CONTRACT);
        assertEquals(1, listener.calls);
        assertEquals("20260715000001", listener.signalId);
        assertEquals("테스트전자", listener.corpName);
        assertEquals("123456", listener.stockCode);
        assertEquals(50_000_000_000L, listener.contractWon);
        assertEquals(OptionalLong.of(40_500_000_000L), listener.revenueWon);
        assertEquals(12.3, listener.salesRatioPct);
    }

    @Test
    void 정정_공시는_자동매매_신호를_보내지_않는다() {
        Disclosure correction = new Disclosure(
                "테스트전자", "00123456", "123456", "Y",
                "[기재정정]단일판매ㆍ공급계약체결", "20260715000003", "20260715", "테스트전자");
        StubDocs docs = new StubDocs();
        docs.contract = new DocumentParser.ContractInfo(
                OptionalLong.of(50_000_000_000L), 12.3, OptionalLong.of(40_500_000_000L), null, null);
        AlertComposer composer = dartOnlyComposer(docs);
        RecordingListener listener = new RecordingListener();
        composer.setTradeSignalListener(listener);
        composer.composeFollowup(correction);
        assertEquals(0, listener.calls);
    }

    // ── 자기주식 3종 (취득·신탁·소각) + 규모 전용 ─────────────────────

    @Test
    void 자기주식_직접취득은_취득금액과_시총대비를_붙인다_DART_원문_경로() {
        StubDocs docs = new StubDocs();
        docs.plainText = "취득예정금액(원) 보통주식 10,000,000,000";
        String msg = dartOnlyComposer(docs).composeTreasury(TREASURY);
        assertEquals("""
                📊 **시총·매출** | 테스트전자 — 주요사항보고서(자기주식취득결정)
                💰 취득금액 100억 · 시총 대비 5.0%
                📈 시총 2,000억 · 매출액 405억""", msg);
    }

    @Test
    void 자기주식_직접취득은_KIND_본문이_있으면_KIND에서_금액을_뽑는다() {
        StubDocs docs = new StubDocs();
        docs.failure = new RuntimeException("DART 경로를 타면 안 됨");
        AlertComposer composer = new AlertComposer(docs, new NewsFilter(),
                new StubQuote(OptionalLong.of(200_000_000_000L)),
                new StubDart(OptionalLong.of(40_500_000_000L)),
                new StubKind("20260715900001"),
                new StubKindDoc("<html><body>취득예정금액(원) 보통주식 7,000,000,000</body></html>", "123456"),
                new DocumentParser());
        String msg = composer.composeTreasury(TREASURY);
        assertEquals("""
                📊 **시총·매출** | 테스트전자 — 주요사항보고서(자기주식취득결정)
                💰 취득금액 70억 · 시총 대비 3.5%
                📈 시총 2,000억 · 매출액 405억""", msg);
    }

    @Test
    void 신탁계약_체결은_계약금액_라벨로_파싱해_신탁계약금액으로_표시한다() {
        Disclosure trust = new Disclosure(
                "테스트전자", "00123456", "123456", "Y",
                "주요사항보고서(자기주식취득신탁계약체결결정)", "20260715000004", "20260715", "테스트전자");
        StubDocs docs = new StubDocs();
        docs.plainText = "계약금액(원) 5,000,000,000";
        String msg = dartOnlyComposer(docs).composeTreasuryTrust(trust);
        assertEquals("""
                📊 **시총·매출** | 테스트전자 — 주요사항보고서(자기주식취득신탁계약체결결정)
                💰 신탁계약금액 50억 · 시총 대비 2.5%
                📈 시총 2,000억 · 매출액 405억""", msg);
    }

    @Test
    void 주식소각은_소각예정금액_라벨로_파싱해_표시한다() {
        Disclosure cancel = new Disclosure(
                "테스트전자", "00123456", "123456", "Y",
                "주요사항보고서(주식소각결정)", "20260715000005", "20260715", "테스트전자");
        StubDocs docs = new StubDocs();
        docs.plainText = "소각예정금액(원) 보통주식 3,000,000,000";
        String msg = dartOnlyComposer(docs).composeCancellation(cancel);
        assertEquals("""
                📊 **시총·매출** | 테스트전자 — 주요사항보고서(주식소각결정)
                💰 소각예정금액 30억 · 시총 대비 1.5%
                📈 시총 2,000억 · 매출액 405억""", msg);
    }

    @Test
    void 금액을_두_소스_모두_못_뽑으면_금액_줄_없이_시총_매출만_보낸다() {
        StubDocs docs = new StubDocs();
        docs.plainText = "금액 라벨이 없는 본문";
        String msg = dartOnlyComposer(docs).composeTreasury(TREASURY);
        assertEquals("""
                📊 **시총·매출** | 테스트전자 — 주요사항보고서(자기주식취득결정)
                📈 시총 2,000억 · 매출액 405억""", msg);
    }

    @Test
    void 자기주식_원문_미공개_014는_전파해_재조회를_트리거한다() {
        StubDocs docs = new StubDocs();
        docs.failure = new DocumentNotReadyException("014");
        AlertComposer composer = dartOnlyComposer(docs);
        assertThrows(DocumentNotReadyException.class, () -> composer.composeTreasury(TREASURY));
    }

    @Test
    void 규모_전용은_비율_없이_시총_매출만_보여준다() {
        Disclosure dividend = new Disclosure(
                "테스트전자", "00123456", "123456", "Y",
                "현금ㆍ현물배당결정", "20260715000006", "20260715", "테스트전자");
        String msg = dartOnlyComposer(new StubDocs()).composeScaleOnly(dividend);
        assertEquals("""
                📊 **시총·매출** | 테스트전자 — 현금ㆍ현물배당결정
                📈 시총 2,000억 · 매출액 405억""", msg);
    }

    @Test
    void 시총_매출_둘_다_없으면_타이틀만_보낸다() {
        AlertComposer composer = new AlertComposer(new StubDocs(), new NewsFilter(),
                new StubQuote(OptionalLong.empty()), new StubDart(OptionalLong.empty()),
                null, null, new DocumentParser());
        Disclosure dividend = new Disclosure(
                "테스트전자", "00123456", "123456", "Y",
                "현금ㆍ현물배당결정", "20260715000006", "20260715", "테스트전자");
        String msg = composer.composeScaleOnly(dividend);
        assertEquals("📊 **시총·매출** | 테스트전자 — 현금ㆍ현물배당결정", msg);
    }
}
