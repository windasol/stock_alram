package com.example.dart.service;

import com.example.dart.quote.StockQuoteClient.PriceSnapshot;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisclosurePriceTrackerTest {

    // classify(mfePct, maePct, peakSec, troughSec) — mfe>=0(최대상승), mae<=0(최대낙폭)

    @Test
    void 변동_거의_없으면_횡보() {
        assertEquals("횡보", DisclosurePriceTracker.classify(0.3, -0.2, 60, 120));
    }

    @Test
    void 오르기만_하면_계속_상승() {
        assertEquals("계속 상승", DisclosurePriceTracker.classify(4.0, -0.1, 300, 30));
    }

    @Test
    void 내리기만_하면_계속_하락() {
        assertEquals("계속 하락", DisclosurePriceTracker.classify(0.2, -3.5, 30, 300));
    }

    @Test
    void 고점이_저점보다_먼저면_올랐다_내림() {
        // 2분에 고점, 8분에 저점 → 올랐다 내림
        assertEquals("올랐다 내림", DisclosurePriceTracker.classify(5.0, -2.0, 120, 480));
    }

    @Test
    void 저점이_고점보다_먼저면_내렸다_오름() {
        // 2분에 저점, 8분에 고점 → 내렸다 오름
        assertEquals("내렸다 오름", DisclosurePriceTracker.classify(3.0, -4.0, 480, 120));
    }

    @Test
    void 경과시간을_분초로_표기() {
        assertEquals("40초", DisclosurePriceTracker.formatDuration(40));
        assertEquals("3분", DisclosurePriceTracker.formatDuration(180));
        assertEquals("2분 10초", DisclosurePriceTracker.formatDuration(130));
        assertEquals("0초", DisclosurePriceTracker.formatDuration(0));
    }

    // isStaleNxt(snapshot, lastExec) — 호가 보완을 쓸지 가르는 핵심 판정.

    @Test
    void 정규장이면_호가_보완_안함() {
        // (a) NXT 세션이 아니면 체결가가 그대로라도 호가를 쓰지 않는다.
        PriceSnapshot regular = new PriceSnapshot(OptionalLong.of(10_000), false, false);
        assertFalse(DisclosurePriceTracker.isStaleNxt(regular, 10_000));
    }

    @Test
    void NXT_체결가가_변했으면_호가_보완_안함() {
        // (b) 직전 체결가(9,900)와 다른 새 체결가(10,000) → 신규 체결 발생 → 체결가 사용.
        PriceSnapshot moved = new PriceSnapshot(OptionalLong.of(10_000), true, true);
        assertFalse(DisclosurePriceTracker.isStaleNxt(moved, 9_900));
    }

    @Test
    void NXT_체결가가_정체면_호가_보완() {
        // (c) 직전 체결가와 같은 값 → 신규 체결 없음(정체) → 호가로 보완.
        PriceSnapshot stuck = new PriceSnapshot(OptionalLong.of(10_000), true, true);
        assertTrue(DisclosurePriceTracker.isStaleNxt(stuck, 10_000));
    }

    @Test
    void NXT_체결이_아예_없으면_호가_보완() {
        // (d) NXT 열렸지만 체결 자체가 없음(execPresent=false) → 호가로 보완.
        PriceSnapshot noExec = new PriceSnapshot(OptionalLong.of(10_000), true, false);
        assertTrue(DisclosurePriceTracker.isStaleNxt(noExec, Long.MIN_VALUE));
    }
}
