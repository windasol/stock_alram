package com.example.dart.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NewsFilterTest {

    private final NewsFilter filter = new NewsFilter();

    @Test
    void 호재_키워드_포함시_true() {
        assertTrue(filter.isGoodNews("단일판매·공급계약체결(자율공시)"));
        assertTrue(filter.isGoodNews("수주공시(자율공시)"));
        assertTrue(filter.isGoodNews("자기주식취득 결정"));
        assertTrue(filter.isGoodNews("무상증자 결정"));
    }

    @Test
    void 제외_키워드_포함시_false() {
        assertFalse(filter.isGoodNews("단일판매·공급계약체결(자율공시)(기재정정)"));
        assertFalse(filter.isGoodNews("감자 결정"));
        assertFalse(filter.isGoodNews("횡령 혐의"));
        assertFalse(filter.isGoodNews("상장폐지 사유 발생"));
    }

    @Test
    void 호재_키워드_미포함시_false() {
        assertFalse(filter.isGoodNews("사업보고서"));
        assertFalse(filter.isGoodNews("분기보고서 (2024.03)"));
    }

    @Test
    void null_또는_빈문자열_false() {
        assertFalse(filter.isGoodNews(null));
        assertFalse(filter.isGoodNews(""));
        assertFalse(filter.isGoodNews("   "));
    }
}
