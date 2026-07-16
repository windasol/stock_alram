package com.example.dart.common.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KoreanMoneyTest {

    @Test
    void 생_숫자_원을_파싱한다() {
        assertEquals(234_000_000_000L, KoreanMoney.parseWon("234,000,000,000").getAsLong());
        assertEquals(234_000_000_000L, KoreanMoney.parseWon("234,000,000,000원").getAsLong());
    }

    @Test
    void 단위_혼합을_파싱한다() {
        assertEquals(1_970L * 1_000_000_000_000L + 1_959L * 100_000_000L,
                KoreanMoney.parseWon("1,970조 1,959억").getAsLong());
        assertEquals(2_340L * 100_000_000L, KoreanMoney.parseWon("2,340억원").getAsLong());
        assertEquals(5_000L * 10_000L, KoreanMoney.parseWon("5,000만원").getAsLong());
    }

    @Test
    void 금액_뒤_다른_텍스트는_무시한다() {
        // 추출값에 다음 항목 텍스트가 붙어도 금액만 합산
        assertEquals(2_340L * 100_000_000L, KoreanMoney.parseWon("2,340억 계약상대방 ABC").getAsLong());
    }

    @Test
    void 숫자가_없으면_empty() {
        assertTrue(KoreanMoney.parseWon("-").isEmpty());
        assertTrue(KoreanMoney.parseWon("").isEmpty());
        assertTrue(KoreanMoney.parseWon(null).isEmpty());
    }

    @Test
    void 원을_사람이_읽는_단위로_포맷한다() {
        assertEquals("2,340억", KoreanMoney.format(2_340L * 100_000_000L));
        assertEquals("1,970조 1,959억", KoreanMoney.format(1_970L * 1_000_000_000_000L + 1_959L * 100_000_000L));
        assertEquals("3,500만", KoreanMoney.format(3_500L * 10_000L));
    }
}
