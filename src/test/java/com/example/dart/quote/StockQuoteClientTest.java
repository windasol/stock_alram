package com.example.dart.quote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockQuoteClientTest {

    private final StockQuoteClient client = new StockQuoteClient();

    @Test
    void integration_JSON에서_시총을_파싱한다() {
        String json = """
                {"itemCode":"005930","stockName":"삼성전자","totalInfos":[
                  {"code":"lastClosePrice","key":"전일","value":"322,500"},
                  {"code":"marketValue","key":"시총","value":"1,970조 1,959억"},
                  {"code":"per","key":"PER","value":"27.24배"}
                ]}""";
        assertEquals(1_970L * 1_000_000_000_000L + 1_959L * 100_000_000L,
                client.parseMarketCap(json).getAsLong());
    }

    @Test
    void 시총_필드가_없으면_empty() {
        assertTrue(client.parseMarketCap("{\"totalInfos\":[]}").isEmpty());
        assertTrue(client.parseMarketCap("not json").isEmpty());
    }

    @Test
    void realtime_JSON에서_현재가를_파싱한다() {
        String json = "{ \"datas\": [ { \"closePrice\": \"357,000\", \"fluctuationsRatio\": \"0.85\" } ] }";
        assertEquals(357_000L, client.parseCurrentPrice(json).getAsLong());
    }

    @Test
    void datas가_비면_현재가_empty() {
        assertTrue(client.parseCurrentPrice("{\"datas\":[]}").isEmpty());
        assertTrue(client.parseCurrentPrice("not json").isEmpty());
    }

    @Test
    void NXT_연장세션이_열려있으면_overPrice를_쓴다() {
        // 애프터마켓 진행 중 — closePrice(정규장 종가)는 고정, 실시간가는 overPrice.
        String json = "{ \"datas\": [ {"
                + " \"closePrice\": \"353,500\","
                + " \"overMarketPriceInfo\": { \"overMarketStatus\": \"OPEN\", \"overPrice\": \"355,000\" }"
                + " } ] }";
        assertEquals(355_000L, client.parseCurrentPrice(json).getAsLong());
    }

    @Test
    void 연장세션이_닫혀있으면_closePrice를_쓴다() {
        // 정규장 — 연장세션 CLOSED면 closePrice가 실시간가.
        String json = "{ \"datas\": [ {"
                + " \"closePrice\": \"353,500\","
                + " \"overMarketPriceInfo\": { \"overMarketStatus\": \"CLOSED\", \"overPrice\": \"0\" }"
                + " } ] }";
        assertEquals(353_500L, client.parseCurrentPrice(json).getAsLong());
    }

    @Test
    void 연장세션_열려도_overPrice가_없으면_closePrice로_폴백() {
        String json = "{ \"datas\": [ {"
                + " \"closePrice\": \"353,500\","
                + " \"overMarketPriceInfo\": { \"overMarketStatus\": \"OPEN\", \"overPrice\": \"\" }"
                + " } ] }";
        assertEquals(353_500L, client.parseCurrentPrice(json).getAsLong());
    }
}
