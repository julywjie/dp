package com.dp.agent.market;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketServiceTest {

    @Test
    void parsesTencentAStockAndHkLines() {
        String text = "v_sh000001=\"1~上证指数~000001~3712.83~3713.03~0.00~0~0~0~0.00~0~0.00~0~0.00"
                + "~0~0.00~0~0.00~0~0.00~0~0.00~0~0.00~0~0.00~0~0.00~~20261022091100"
                + "~-0.20~-0.01~0.00~0.00~\";\n"
                + "v_hkHSTECH=\"100~恒生科技指数~HSTECH~3994.770~4045.910~4110.740~4656005.2698"
                + "~0~0~3994.770~0~0~0~0~0~0~0~0~0~3994.770~0~0~0~0~0~0~0~0~0~0.0"
                + "~2026/10/21 16:08:42~-51.140~-1.26~4110.740~3985.630~\";";

        List<MarketQuote> quotes = MarketParsers.parseTencent(text);
        assertEquals(2, quotes.size());

        MarketQuote sh = quotes.get(0);
        assertEquals("sh000001", sh.key());
        assertEquals("上证指数", sh.name());
        assertEquals(3712.83, sh.price(), 0.001);
        assertEquals(-0.01, sh.changePercent(), 0.001);
        assertTrue(sh.updatedAt() > 0);

        MarketQuote hk = quotes.get(1);
        assertEquals("hstech", hk.key());
        assertEquals(3994.77, hk.price(), 0.001);
        assertEquals(-1.26, hk.changePercent(), 0.001);
        assertTrue(hk.updatedAt() > 0);
    }

    @Test
    void ignoresUnknownSymbolsAndEmptyPayloads() {
        assertTrue(MarketParsers.parseTencent("v_sh999999=\"1~x~999999~1~1~1~0~0~0~0~0~0"
                + "~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~\";").isEmpty());
        assertTrue(MarketParsers.parseTencent("").isEmpty());
        assertTrue(MarketParsers.parseTencent(null).isEmpty());
    }

    @Test
    void parsesEastmoneyStockGetFields() throws Exception {
        String json = "{\"rc\":0,\"data\":{\"f43\":2902.18,\"f47\":12345678,"
                + "\"f48\":9876543210,\"f50\":1.23,\"f57\":\"932000\","
                + "\"f58\":\"中证2000\",\"f86\":1792631457,\"f169\":-0.05,\"f170\":-0.17}}";

        List<MarketQuote> quotes = MarketParsers.parseEastmoney(json);
        assertEquals(1, quotes.size());
        MarketQuote csi = quotes.get(0);
        assertEquals("csi2000", csi.key());
        assertEquals(2902.18, csi.price(), 0.001);
        assertEquals(-0.05, csi.change(), 0.001);
        assertEquals(-0.17, csi.changePercent(), 0.001);
        assertEquals(1_234_567_800, csi.volume(), 0.001);
        assertEquals(9_876_543_210.0, csi.amount(), 0.001);
        assertEquals(1.23, csi.volumeRatio(), 0.001);
        assertEquals(1792631457000L, csi.updatedAt());
    }

    @Test
    void parsesBreadthAcrossMarkets() throws Exception {
        String json = "{\"data\":{\"diff\":["
                + "{\"f104\":1200,\"f105\":800,\"f106\":100},"
                + "{\"f104\":1500,\"f105\":1000,\"f106\":120}]}}";

        MarketBreadth breadth = MarketParsers.parseBreadth(json);
        assertEquals(2700, breadth.up());
        assertEquals(1800, breadth.down());
        assertEquals(220, breadth.flat());
        assertTrue(breadth.isValid());
    }

    @Test
    void rejectsInvalidBreadth() {
        assertThrows(java.io.IOException.class,
                () -> MarketParsers.parseBreadth("{\"data\":{\"diff\":[]}}"));
        assertThrows(java.io.IOException.class,
                () -> MarketParsers.parseBreadth("{\"data\":{}}"));
    }

    @Test
    void parsesSinaFallbackLines() {
        String text = "var hq_str_s_sh000001=\"上证指数,3712.8297,-0.1980,-0.01,1000,20000\";\n"
                + "var hq_str_s_sh000300=\"沪深300,4111.6495,-0.2830,-0.01,1000,20000\";\n"
                + "var hq_str_hkHSTECH=\"HSTECH,恒生科技指数,4110.740,4045.910,4110.740,"
                + "3985.630,3994.770,-51.140,-1.264,0.0,0.0,46560052,985275303,0.0,0.0,0.0,"
                + "2026/10/21,16:08\";";

        List<MarketQuote> quotes = MarketParsers.parseSina(text);
        assertEquals(3, quotes.size());
        assertEquals("sh000001", quotes.get(0).key());
        assertEquals(3712.83, quotes.get(0).price(), 0.01);
        assertEquals(-0.01, quotes.get(0).changePercent(), 0.001);
        assertEquals(10_000_000, quotes.get(0).volume(), 0.001);
        assertEquals(200_000_000, quotes.get(0).amount(), 0.001);
        assertEquals("hs300", quotes.get(1).key());
        assertEquals(4111.65, quotes.get(1).price(), 0.01);
        assertEquals("hstech", quotes.get(2).key());
        assertEquals(3994.77, quotes.get(2).price(), 0.001);
        assertEquals(-1.264, quotes.get(2).changePercent(), 0.001);
        assertEquals(46_560_052, quotes.get(2).volume(), 0.001);
        assertEquals(985_275_303, quotes.get(2).amount(), 0.001);
        assertTrue(quotes.get(2).updatedAt() > 0);
    }

    @Test
    void parsesTencentVolumeAmountAndRatio() {
        String[] parts = new String[51];
        Arrays.fill(parts, "0");
        parts[0] = "1";
        parts[1] = "上证指数";
        parts[2] = "000001";
        parts[3] = "3700.00";
        parts[4] = "3690.00";
        parts[5] = "3695.00";
        parts[6] = "1000000"; // 手
        parts[30] = "20261022100000";
        parts[31] = "10.00";
        parts[32] = "0.27";
        parts[35] = "3700.00/1000000/3700000000";
        parts[49] = "1.55";

        List<MarketQuote> quotes = MarketParsers.parseTencent(
                "v_sh000001=\"" + String.join("~", parts) + "\";");
        assertEquals(1, quotes.size());
        MarketQuote quote = quotes.get(0);
        assertEquals(100_000_000, quote.volume(), 0.001);
        assertEquals(3_700_000_000.0, quote.amount(), 0.001);
        assertEquals(1.55, quote.volumeRatio(), 0.001);
    }

    @Test
    void mergeMissingKeepsPrimaryValues() {
        MarketQuote primarySh = new MarketQuote("sh000001", "上证指数", 3700, 1, 0.1);
        MarketQuote fallbackSh = new MarketQuote("sh000001", "上证指数", 9999, 0, 0);
        MarketQuote fallbackHk = new MarketQuote("hstech", "恒生科技", 3994.77, -51, -1.26);

        List<MarketQuote> merged = MarketParsers.mergeMissing(
                List.of(primarySh), List.of(fallbackSh, fallbackHk));

        assertEquals(2, merged.size());
        assertEquals(3700, merged.get(0).price(), 0.001);
        assertEquals("hstech", merged.get(1).key());
    }

    @Test
    void emptyEastmoneyResponseIsSafe() throws Exception {
        assertTrue(MarketParsers.parseEastmoney("{}").isEmpty());
        assertTrue(MarketParsers.parseEastmoney("").isEmpty());
        assertTrue(MarketParsers.parseEastmoney(
                "{\"data\":{\"f43\":0,\"f57\":\"932000\"}}").isEmpty());
        assertTrue(MarketParsers.parseEastmoney(
                "{\"data\":{\"f43\":62576.9,\"f57\":\"DJI\",\"f58\":\"道琼斯\"}}").isEmpty());
    }

    @Test
    void parsesEastmoneyOverseasIndices() throws Exception {
        String nikkei = "{\"rc\":0,\"data\":{\"f43\":62576.9,\"f47\":0,\"f48\":\"-\","
                + "\"f50\":\"-\",\"f57\":\"N225\",\"f58\":\"日经225\","
                + "\"f86\":1792978672,\"f169\":-116.26,\"f170\":-0.19}}";
        String kospi = "{\"rc\":0,\"data\":{\"f43\":5513.22,\"f47\":79753,\"f48\":\"-\","
                + "\"f50\":1.33,\"f57\":\"KS11\",\"f58\":\"韩国KOSPI\","
                + "\"f86\":1792978700,\"f169\":-37.08,\"f170\":-0.67}}";
        String a50 = "{\"rc\":0,\"data\":{\"f43\":13021.24,\"f47\":0,\"f48\":\"-\","
                + "\"f50\":\"-\",\"f57\":\"XIN9\",\"f58\":\"富时中国A50\","
                + "\"f86\":1793084405,\"f169\":-153.18,\"f170\":-1.16}}";

        MarketQuote n225 = MarketParsers.parseEastmoney(nikkei).get(0);
        assertEquals("nikkei225", n225.key());
        assertEquals("日经225", n225.name());
        assertEquals(62576.9, n225.price(), 0.001);
        assertEquals(-116.26, n225.change(), 0.001);
        assertEquals(-0.19, n225.changePercent(), 0.001);
        assertEquals(1792978672000L, n225.updatedAt());
        // 海外指数不解析成交量/额（单位不明），量比无量纲可解析；日经源站不提供
        assertEquals(0, n225.volume(), 0.001);
        assertEquals(0, n225.amount(), 0.001);
        assertEquals(0, n225.volumeRatio(), 0.001);

        MarketQuote ks11 = MarketParsers.parseEastmoney(kospi).get(0);
        assertEquals("kospi", ks11.key());
        assertEquals("韩国KOSPI", ks11.name());
        assertEquals(5513.22, ks11.price(), 0.001);
        assertEquals(-0.67, ks11.changePercent(), 0.001);
        assertEquals(0, ks11.volume(), 0.001);
        assertEquals(1.33, ks11.volumeRatio(), 0.001);

        MarketQuote ftse = MarketParsers.parseEastmoney(a50).get(0);
        assertEquals("ftseA50", ftse.key());
        assertEquals("富时中国A50", ftse.name());
        assertEquals(13021.24, ftse.price(), 0.001);
        assertEquals(-153.18, ftse.change(), 0.001);
        assertEquals(-1.16, ftse.changePercent(), 0.001);
        assertEquals(1793084405000L, ftse.updatedAt());
        assertEquals(0, ftse.volume(), 0.001);
        assertEquals(0, ftse.amount(), 0.001);
        assertEquals(0, ftse.volumeRatio(), 0.001);
    }

    @Test
    void parsesTencentChiNextAndStar50() {
        List<MarketQuote> quotes = MarketParsers.parseTencent(
                tencentLine("sz399006", "创业板指", "399006", "2497.61",
                        "81142247", "186418504201", "-79.29", "-3.08", "1.49")
                        + "\n"
                        + tencentLine("sh000688", "科创50", "000688", "1259.88",
                        "3320320", "30674022039", "-45.99", "-3.52", "0.50"));

        assertEquals(2, quotes.size());
        MarketQuote chiNext = quotes.get(0);
        assertEquals("chinext", chiNext.key());
        assertEquals("创业板指", chiNext.name());
        assertEquals(2497.61, chiNext.price(), 0.001);
        assertEquals(-3.08, chiNext.changePercent(), 0.001);
        assertEquals(8_114_224_700.0, chiNext.volume(), 0.001);
        assertEquals(186_418_504_201.0, chiNext.amount(), 0.001);
        assertEquals(1.49, chiNext.volumeRatio(), 0.001);

        MarketQuote star = quotes.get(1);
        assertEquals("star50", star.key());
        assertEquals("科创50", star.name());
        assertEquals(1259.88, star.price(), 0.001);
        assertEquals(-3.52, star.changePercent(), 0.001);
        assertEquals(332_032_000.0, star.volume(), 0.001);
        assertEquals(0.50, star.volumeRatio(), 0.001);
    }

    @Test
    void parsesSinaChiNextAndStar50() {
        String text = "var hq_str_s_sz399006=\"创业板指,2497.41,-79.489,-3.08,11702670,7291904\";\n"
                + "var hq_str_s_sh000688=\"科创50,1259.8844,-45.9887,-3.52,33203,3067402\";";

        List<MarketQuote> quotes = MarketParsers.parseSina(text);

        assertEquals(2, quotes.size());
        MarketQuote chiNext = quotes.get(0);
        assertEquals("chinext", chiNext.key());
        assertEquals("创业板指", chiNext.name());
        assertEquals(2497.41, chiNext.price(), 0.01);
        assertEquals(-3.08, chiNext.changePercent(), 0.001);
        assertEquals(117_026_700_000.0, chiNext.volume(), 0.001);
        assertEquals(72_919_040_000.0, chiNext.amount(), 0.001);

        MarketQuote star = quotes.get(1);
        assertEquals("star50", star.key());
        assertEquals(1259.88, star.price(), 0.01);
        assertEquals(-3.52, star.changePercent(), 0.001);
        assertEquals(332_030_000.0, star.volume(), 0.001);
    }

    private static String tencentLine(String symbol, String name, String code,
                                      String price, String volume, String amount,
                                      String change, String percent, String ratio) {
        String[] parts = new String[88];
        Arrays.fill(parts, "0");
        parts[1] = name;
        parts[2] = code;
        parts[3] = price;
        parts[6] = volume;
        parts[30] = "20261023102057";
        parts[31] = change;
        parts[32] = percent;
        parts[35] = price + "/" + volume + "/" + amount;
        parts[49] = ratio;
        return "v_" + symbol + "=\"" + String.join("~", parts) + "\";";
    }
}
