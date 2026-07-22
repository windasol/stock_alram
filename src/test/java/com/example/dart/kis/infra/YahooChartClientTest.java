package com.example.dart.kis.infra;

import com.example.dart.kis.infra.YahooChartClient.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YahooChartClient의 순수 파싱 로직 단위 테스트 — 네트워크 없이 야후 차트 응답 파싱만 검증한다.
 * (실제 호출 검증은 스모크 스크립트가 담당.)
 */
class YahooChartClientTest {

    @Test
    void 정상_응답이면_현재가와_등락률() {
        String body = """
                {"chart":{"result":[{"meta":{
                  "regularMarketPrice":2680.0,
                  "previousClose":2700.0
                }}],"error":null}}
                """;
        Optional<Snapshot> snap = YahooChartClient.parseSnapshot(body);
        assertTrue(snap.isPresent());
        assertEquals(2680.0, snap.get().price(), 0.001);
        assertEquals(-0.7407, snap.get().pct(), 0.01);
    }

    @Test
    void previousClose가_없으면_chartPreviousClose로_폴백() {
        String body = """
                {"chart":{"result":[{"meta":{
                  "regularMarketPrice":100.0,
                  "chartPreviousClose":80.0
                }}]}}
                """;
        Optional<Snapshot> snap = YahooChartClient.parseSnapshot(body);
        assertTrue(snap.isPresent());
        assertEquals(25.0, snap.get().pct(), 0.001);
    }

    @Test
    void meta가_없으면_empty() {
        assertFalse(YahooChartClient.parseSnapshot("{\"chart\":{\"result\":[]}}").isPresent());
    }

    @Test
    void 전일가가_0이면_empty() {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":100.0,\"previousClose\":0}}]}}";
        assertFalse(YahooChartClient.parseSnapshot(body).isPresent());
    }

    @Test
    void 깨진_JSON이면_empty() {
        assertFalse(YahooChartClient.parseSnapshot("not-json{").isPresent());
    }
}
