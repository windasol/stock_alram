package com.example.dart.kis.infra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * UsFuturesClient의 순수 포맷 로직 단위 테스트. 야후 응답 파싱은 {@link YahooChartClientTest}가 담당한다.
 */
class UsFuturesClientTest {

    @Test
    void 포맷은_라벨과_부호붙은_퍼센트를_쉼표로_잇는다() {
        String line = UsFuturesClient.formatSummary(List.of(
                new UsFuturesClient.Quote("S&P", 0.42),
                new UsFuturesClient.Quote("나스닥", -0.61)));
        assertEquals("🌎 **미국 선물** | S&P +0.4%, 나스닥 -0.6%", line);
    }

    @Test
    void 포맷_빈목록이면_null() {
        assertNull(UsFuturesClient.formatSummary(List.of()));
    }
}
