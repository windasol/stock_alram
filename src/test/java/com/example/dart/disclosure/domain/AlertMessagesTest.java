package com.example.dart.disclosure.domain;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AlertMessages#contractBody}의 출력 스냅샷 — DART {@code followup}와 KIND {@code composeFollowup}이
 * 공유하는 순수 조립부(Phase 7a 통합)의 회귀를 막는다. 기대 문자열은 통합 전 로직으로 손계산한 값.
 */
class AlertMessagesTest {

    @Test
    void 전체_필드_계약금액_매출대비명시_시총대비() {
        ContractInfo c = new ContractInfo(
                OptionalLong.of(234_000_000_000L), 15.5, OptionalLong.empty(),
                "현대자동차", "2026-01-01 ~ 2026-12-31");

        String out = AlertMessages.contractBody(
                false, "테스트회사", "단일판매·공급계약체결",
                c, OptionalLong.of(5_000_000_000_000L), OptionalLong.of(100_000_000_000L));

        assertEquals(
                "📊 **시총·매출 대비** | 테스트회사 — 단일판매·공급계약체결\n"
                        + "💰 계약금액 2,340억 · 매출 대비 15.5% · 시총 대비 4.7%\n"
                        + "📈 시총 5조 · 매출액 1,000억 · 계약상대방 현대자동차 · 계약기간 2026-01-01 ~ 2026-12-31",
                out);
    }

    @Test
    void 정정_계약금액없음_시총만() {
        ContractInfo c = new ContractInfo(OptionalLong.empty(), null, OptionalLong.empty(), null, null);

        String out = AlertMessages.contractBody(
                true, "회사", "공시", c, OptionalLong.of(5_000_000_000_000L), OptionalLong.empty());

        assertEquals("📊 **[정정] 시총·매출 대비** | 회사 — 공시\n📈 시총 5조", out);
    }

    @Test
    void 매출대비_계산경로_시총없음() {
        // salesRatioPct 미지정 → 계약금액÷매출액으로 계산(100_000_000_000 ÷ 500_000_000_000 = 20.0%)
        ContractInfo c = new ContractInfo(
                OptionalLong.of(100_000_000_000L), null, OptionalLong.empty(), null, null);

        String out = AlertMessages.contractBody(
                false, "A", "B", c, OptionalLong.empty(), OptionalLong.of(500_000_000_000L));

        assertEquals(
                "📊 **시총·매출 대비** | A — B\n"
                        + "💰 계약금액 1,000억 · 매출 대비 20.0%\n"
                        + "📈 매출액 5,000억",
                out);
    }
}
