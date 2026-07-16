package com.example.dart.kis.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UsFuturesClient의 순수 파싱·포맷 로직 단위 테스트 — 네트워크 없이 야후 응답 파싱만 검증한다.
 * (실제 호출 검증은 us_futures_smoketest.ps1 이 담당.)
 */
class UsFuturesClientTest {

    private final UsFuturesClient client = new UsFuturesClient();

    @Test
    void 정상_응답이면_전일대비_등락률() {
        String body = """
                {"chart":{"result":[{"meta":{
                  "regularMarketPrice":5234.5,
                  "previousClose":5210.0
                }}],"error":null}}
                """;
        OptionalDouble pct = client.parseChangePct(body);
        assertTrue(pct.isPresent());
        assertEquals(0.4702, pct.getAsDouble(), 0.01);
    }

    @Test
    void previousClose가_없으면_chartPreviousClose로_폴백() {
        String body = """
                {"chart":{"result":[{"meta":{
                  "regularMarketPrice":100.0,
                  "chartPreviousClose":80.0
                }}]}}
                """;
        OptionalDouble pct = client.parseChangePct(body);
        assertTrue(pct.isPresent());
        assertEquals(25.0, pct.getAsDouble(), 0.001);
    }

    @Test
    void meta가_없으면_empty() {
        assertFalse(client.parseChangePct("{\"chart\":{\"result\":[]}}").isPresent());
    }

    @Test
    void 전일가가_0이면_empty() {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":100.0,\"previousClose\":0}}]}}";
        assertFalse(client.parseChangePct(body).isPresent());
    }

    @Test
    void 깨진_JSON이면_empty() {
        assertFalse(client.parseChangePct("not-json{").isPresent());
    }

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
