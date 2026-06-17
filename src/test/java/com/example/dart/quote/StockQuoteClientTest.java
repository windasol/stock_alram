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
}
