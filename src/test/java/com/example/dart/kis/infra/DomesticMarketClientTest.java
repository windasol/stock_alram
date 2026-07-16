package com.example.dart.kis.infra;

import com.example.dart.kis.infra.DomesticMarketClient.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DomesticMarketClient의 순수 파싱·포맷 로직 단위 테스트 — 네트워크 없이 야후 응답 파싱만 검증한다.
 * (실제 호출 검증은 스모크 스크립트가 담당.)
 */
class DomesticMarketClientTest {

    private final DomesticMarketClient client = new DomesticMarketClient();

    @Test
    void 정상_응답이면_현재가와_등락률() {
        String body = """
                {"chart":{"result":[{"meta":{
                  "regularMarketPrice":2680.0,
                  "previousClose":2700.0
                }}],"error":null}}
                """;
        Optional<Snapshot> snap = client.parseSnapshot(body);
        assertTrue(snap.isPresent());
        assertEquals(2680.0, snap.get().price(), 0.001);
        assertEquals(-0.7407, snap.get().pct(), 0.01);
    }

    @Test
    void previousClose가_없으면_chartPreviousClose로_폴백() {
        String body = """
                {"chart":{"result":[{"meta":{
                  "regularMarketPrice":1350.0,
                  "chartPreviousClose":1338.0
                }}]}}
                """;
        Optional<Snapshot> snap = client.parseSnapshot(body);
        assertTrue(snap.isPresent());
        assertEquals(0.8969, snap.get().pct(), 0.01);
    }

    @Test
    void meta가_없으면_empty() {
        assertFalse(client.parseSnapshot("{\"chart\":{\"result\":[]}}").isPresent());
    }

    @Test
    void 전일가가_0이면_empty() {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":100.0,\"previousClose\":0}}]}}";
        assertFalse(client.parseSnapshot(body).isPresent());
    }

    @Test
    void 깨진_JSON이면_empty() {
        assertFalse(client.parseSnapshot("not-json{").isPresent());
    }

    @Test
    void 지수_포맷은_라벨과_부호붙은_퍼센트를_쉼표로_잇는다() {
        String line = DomesticMarketClient.formatIndexSummary(List.of(
                new DomesticMarketClient.Quote("코스피", -0.81),
                new DomesticMarketClient.Quote("코스닥", 0.52)));
        assertEquals("🇰🇷 **국내 지수** | 코스피 -0.8%, 코스닥 +0.5%", line);
    }

    @Test
    void 지수_포맷_빈목록이면_null() {
        assertNull(DomesticMarketClient.formatIndexSummary(List.of()));
    }

    @Test
    void 지수_헤드라인은_값과_화살표와_부호붙은_퍼센트를_가운뎃점으로_잇는다() {
        String line = DomesticMarketClient.formatIndexHeadline(List.of(
                new DomesticMarketClient.IndexQuote("코스피", 2750.32, 0.82),
                new DomesticMarketClient.IndexQuote("코스닥", 850.10, -0.35)));
        assertEquals("🇰🇷 코스피 2,750.32 ▲ +0.82% · 코스닥 850.10 ▼ -0.35%", line);
    }

    @Test
    void 지수_헤드라인_보합이면_대시_화살표() {
        String line = DomesticMarketClient.formatIndexHeadline(List.of(
                new DomesticMarketClient.IndexQuote("코스피", 2700.00, 0.0)));
        assertEquals("🇰🇷 코스피 2,700.00 — +0.00%", line);
    }

    @Test
    void 지수_헤드라인_빈목록이면_null() {
        assertNull(DomesticMarketClient.formatIndexHeadline(List.of()));
    }

    @Test
    void 환율_포맷은_천단위쉼표_레벨과_등락률() {
        String line = DomesticMarketClient.formatFx(new Snapshot(1350.2, 0.9));
        assertEquals("💱 **원달러** | 1,350.2원 (+0.9%)", line);
    }

    @Test
    void 네이버_지수_투자자트렌드를_파싱하고_억원을_원으로_환산한다() {
        // 예: 코스피 개인 +26,018억 · 외국인 -16,042억 · 기관 -10,933억 (실측값 형식). 부호·콤마 포함.
        String json = "{\"bizdate\":\"20260706\",\"personalValue\":\"+26,018\","
                + "\"foreignValue\":\"-16,042\",\"institutionalValue\":\"-10,933\"}";
        Optional<DomesticMarketClient.InvestorNet> n = DomesticMarketClient.parseInvestorNet(json, "코스피");
        assertTrue(n.isPresent());
        assertEquals("코스피", n.get().market());
        assertEquals(26_018L * 100_000_000L, n.get().individualWon());    // 개인 +2조6,018억(순매수)
        assertEquals(-16_042L * 100_000_000L, n.get().foreignWon());      // 외국인 -1조6,042억(순매도)
        assertEquals(-10_933L * 100_000_000L, n.get().institutionWon());  // 기관 -1조933억(순매도)
    }

    @Test
    void 투자자트렌드_세값이_전부_0이거나_빈값이면_empty() {
        // 장 시작 전 등 값이 없을 때는 헤드라인을 보내지 않도록 empty.
        assertFalse(DomesticMarketClient.parseInvestorNet(
                "{\"personalValue\":\"0\",\"foreignValue\":\"0\",\"institutionalValue\":\"0\"}", "코스피").isPresent());
        assertFalse(DomesticMarketClient.parseInvestorNet("{}", "코스닥").isPresent());
        assertFalse(DomesticMarketClient.parseInvestorNet("not json", "코스피").isPresent());
    }

    @Test
    void 부호_콤마_억원_문자열을_원으로_환산한다() {
        assertEquals(26_018L * 100_000_000L, DomesticMarketClient.parseEokWon("+26,018"));
        assertEquals(-16_042L * 100_000_000L, DomesticMarketClient.parseEokWon("-16,042"));
        assertEquals(0L, DomesticMarketClient.parseEokWon(""));
        assertEquals(0L, DomesticMarketClient.parseEokWon("-"));
        assertEquals(0L, DomesticMarketClient.parseEokWon("N/A"));
    }
}
