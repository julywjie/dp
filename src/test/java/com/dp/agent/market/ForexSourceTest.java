package com.dp.agent.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForexSourceTest {

    @Test
    void parsesSinaUsdCnh() {
        String text = "var hq_str_fx_susdcnh=\"09:26:10,6.672000,6.672200,6.672600,37,"
                + "6.671600,6.673700,6.670000,6.672000,离岸人民币（香港）,-0.010000,"
                + "-0.000600,0.0005546,,6.995700,6.670700,,2026-10-23\";";

        MarketQuote quote = ForexSource.parseSina(text).orElseThrow();

        assertEquals(MarketService.USDCNH_KEY, quote.key());
        assertEquals("美元离岸人民币", quote.name());
        assertEquals(6.672, quote.price(), 1e-9);
        assertEquals(-0.0006, quote.change(), 1e-9);
        assertEquals(-0.01, quote.changePercent(), 1e-9);
        assertEquals(0, quote.volume(), 0);
        assertTrue(quote.hasValue());
        assertTrue(quote.updatedAt() > 0);
    }

    @Test
    void parsesEastmoneyUsdCnh() throws Exception {
        String json = "{\"rc\":0,\"data\":{\"f43\":6.672,\"f57\":\"USDCNH\","
                + "\"f58\":\"美元兑离岸人民币\",\"f86\":1792718806,"
                + "\"f169\":-0.0007,\"f170\":-0.01}}";

        MarketQuote quote = ForexSource.parseEastmoney(json).orElseThrow();

        assertEquals(MarketService.USDCNH_KEY, quote.key());
        assertEquals(6.672, quote.price(), 1e-9);
        assertEquals(-0.0007, quote.change(), 1e-9);
        assertEquals(-0.01, quote.changePercent(), 1e-9);
        assertEquals(1792718806000L, quote.updatedAt());
    }

    @Test
    void ignoresWrongSymbolEmptyOrInvalidPayloads() throws Exception {
        assertTrue(ForexSource.parseSina(null).isEmpty());
        assertTrue(ForexSource.parseSina("").isEmpty());
        assertTrue(ForexSource.parseSina(
                "var hq_str_fx_susdcny=\"03:00:01,6.6745,6.6750,6.6748,47,6.6750,"
                        + "6.6759,6.6712,6.6748,在岸人民币,0.0000,0.0000,0.0047,"
                        + ",0.0000,0.0000,,2026-10-23\";").isEmpty());

        assertTrue(ForexSource.parseEastmoney("{}").isEmpty());
        assertTrue(ForexSource.parseEastmoney("").isEmpty());
        assertTrue(ForexSource.parseEastmoney(
                "{\"data\":{\"f43\":2902.18,\"f57\":\"932000\"}}").isEmpty());
        assertTrue(ForexSource.parseEastmoney(
                "{\"data\":{\"f43\":0,\"f57\":\"USDCNH\"}}").isEmpty());
    }
}
