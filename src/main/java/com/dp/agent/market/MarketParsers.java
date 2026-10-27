package com.dp.agent.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行情源解析：腾讯 / 新浪 / 东方财富文本与 JSON → {@link MarketQuote} / {@link MarketBreadth}。
 * 纯函数无状态、不发起网络请求；字段位含义与单位换算见各方法注释。
 */
final class MarketParsers {

    /** 腾讯/新浪行情响应为 GBK 系编码。 */
    static final Charset GBK = Charset.forName("GB18030");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TENCENT_LINE =
            Pattern.compile("v_([A-Za-z0-9]+)=\"([^\"]*)\"");
    private static final Pattern SINA_LINE =
            Pattern.compile("hq_str_([A-Za-z0-9_]+)=\"([^\"]*)\"");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS14 =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TS_HK =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter TS_SINA =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private MarketParsers() {
    }

    /** 先保留主源结果，只把兜底源里缺失的指标补进来。 */
    static List<MarketQuote> mergeMissing(List<MarketQuote> primary,
                                          List<MarketQuote> fallback) {
        List<MarketQuote> merged = new ArrayList<>(primary);
        Set<String> keys = new HashSet<>();
        for (MarketQuote quote : primary) {
            keys.add(quote.key());
        }
        for (MarketQuote quote : fallback) {
            if (keys.add(quote.key())) {
                merged.add(quote);
            }
        }
        return merged;
    }

    /**
     * 腾讯字段位：1 名称 / 3 最新价 / 时间戳之后两位依次是涨跌额、涨跌幅。
     * A 股与港股的字段长度不同（88 vs 78），因此以时间戳定位而不是硬编码下标。
     */
    static List<MarketQuote> parseTencent(String text) {
        List<MarketQuote> quotes = new ArrayList<>();
        if (text == null) {
            return quotes;
        }
        Matcher matcher = TENCENT_LINE.matcher(text);
        while (matcher.find()) {
            String symbol = matcher.group(1).toLowerCase(Locale.ROOT);
            String[] parts = matcher.group(2).split("~", -1);
            MarketQuote quote = tencentQuote(symbol, parts);
            if (quote != null) {
                quotes.add(quote);
            }
        }
        return quotes;
    }

    private static MarketQuote tencentQuote(String symbol, String[] parts) {
        if (parts.length < 8) {
            return null;
        }
        String key = switch (symbol) {
            case "sh000001" -> "sh000001";
            case "sh000300" -> "hs300";
            case "hkhstech" -> "hstech";
            case "sz399006" -> "chinext";
            case "sh000688" -> "star50";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        double price = number(parts[3]);
        if (price <= 0) {
            return null;
        }
        int stamp = timestampIndex(parts);
        double change = stamp >= 0 && stamp + 2 < parts.length
                ? number(parts[stamp + 1]) : 0;
        double percent = stamp >= 0 && stamp + 2 < parts.length
                ? number(parts[stamp + 2]) : 0;
        long updatedAt = stamp >= 0 ? parseTencentTime(parts[stamp]) : 0;
        boolean hk = "hstech".equals(key);
        // A 股成交量单位为手（×100 股），港股腾讯按 10 股为单位
        double volume = parts.length > 6
                ? number(parts[6]) * (hk ? 10 : 100) : 0;
        double amount = hk ? 0 : tencentAmount(parts);
        double ratio = 0;
        if (hk && parts.length > 50) {
            ratio = number(parts[50]);
        } else if (!hk && parts.length > 49) {
            ratio = number(parts[49]);
        }
        return new MarketQuote(key, parts[1], price, change, percent,
                volume, amount, ratio, updatedAt);
    }

    /** 腾讯 field 35 形如「价格/成交量/成交额(元)」。 */
    private static double tencentAmount(String[] parts) {
        if (parts.length <= 35) {
            return 0;
        }
        String[] segments = parts[35].split("/");
        return segments.length >= 3 ? number(segments[2]) : 0;
    }

    private static long parseTencentTime(String token) {
        try {
            String value = token.trim();
            if (value.matches("\\d{14}")) {
                return LocalDateTime.parse(value, TS14)
                        .atZone(MARKET_ZONE).toInstant().toEpochMilli();
            }
            if (value.matches("\\d{4}/\\d{2}/\\d{2} .+")) {
                return LocalDateTime.parse(value, TS_HK)
                        .atZone(MARKET_ZONE).toInstant().toEpochMilli();
            }
        } catch (RuntimeException ignored) {
            // 源站时间格式变化时退回“无时间”，不影响价格展示
        }
        return 0;
    }

    private static int timestampIndex(String[] parts) {
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.matches("\\d{14}") || token.matches("\\d{4}/\\d{2}/\\d{2} .+")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 新浪兜底：
     *   A 股 s_sh000001="名称,最新价,涨跌额,涨跌幅,..."
     *   港股 hkHSTECH="代码,名称,今开,昨收,最高,最低,最新价,涨跌额,涨跌幅,..."
     */
    static List<MarketQuote> parseSina(String text) {
        List<MarketQuote> quotes = new ArrayList<>();
        if (text == null) {
            return quotes;
        }
        Matcher matcher = SINA_LINE.matcher(text);
        while (matcher.find()) {
            String symbol = matcher.group(1).toLowerCase(Locale.ROOT);
            String[] parts = matcher.group(2).split(",", -1);
            MarketQuote quote = "hkhstech".equals(symbol)
                    ? sinaHkQuote(parts)
                    : sinaSimpleQuote(symbol, parts);
            if (quote != null) {
                quotes.add(quote);
            }
        }
        return quotes;
    }

    /** 新浪 A 股简版：s_ 前缀 6 字段，成交量/成交额单位为万股/万元。 */
    private static MarketQuote sinaSimpleQuote(String symbol, String[] parts) {
        if (parts.length < 6) {
            return null;
        }
        String key = switch (symbol) {
            case "s_sh000001" -> "sh000001";
            case "s_sh000300" -> "hs300";
            case "s_sz399006" -> "chinext";
            case "s_sh000688" -> "star50";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        double price = number(parts[1]);
        if (price <= 0) {
            return null;
        }
        return new MarketQuote(key, parts[0], price,
                number(parts[2]), number(parts[3]),
                number(parts[4]) * 10_000,  // 万股 -> 股
                number(parts[5]) * 10_000,  // 万元 -> 元
                0, 0);
    }

    /** 新浪港股行：最新价在 7，行末两位是「日期,时间」。 */
    private static MarketQuote sinaHkQuote(String[] parts) {
        if (parts.length < 13) {
            return null;
        }
        double price = number(parts[6]);
        if (price <= 0) {
            return null;
        }
        return new MarketQuote("hstech", parts[1], price,
                number(parts[7]), number(parts[8]),
                number(parts[11]), number(parts[12]), 0,
                sinaHkTime(parts));
    }

    private static long sinaHkTime(String[] parts) {
        String date = parts[parts.length - 2].trim();
        String time = parts[parts.length - 1].trim();
        if (!date.matches("\\d{4}/\\d{2}/\\d{2}") || !time.matches("\\d{2}:\\d{2}")) {
            return 0;
        }
        try {
            return LocalDateTime.parse(date + " " + time, TS_SINA)
                    .atZone(MARKET_ZONE).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * 东方财富 stock/get：fltt=2 已换算为小数，f43 最新价 / f169 涨跌额 / f170 涨跌幅。
     * f57 为源站代码：932000 中证2000 / XIN9 富时中国A50 / N225 日经225 / KS11 韩国KOSPI，
     * 未知代码忽略。海外指数源站不提供成交额（f48 为 "-"）且成交量单位与 A 股的手不一致，
     * 不解析成交量/成交额以免误标单位；量比为无量纲比值，可以安全解析
     * （日经225 源站不提供，自然为 0）。
     */
    static List<MarketQuote> parseEastmoney(String json) throws IOException {
        List<MarketQuote> quotes = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return quotes;
        }
        JsonNode data = MAPPER.readTree(json).path("data");
        if (!data.isObject()) {
            return quotes;
        }
        String code = data.path("f57").asText("");
        double price = data.path("f43").asDouble(0);
        String key = switch (code) {
            case "932000" -> "csi2000";
            case "XIN9" -> "ftseA50";
            case "N225" -> "nikkei225";
            case "KS11" -> "kospi";
            default -> null;
        };
        if (key == null || price <= 0) {
            return quotes;
        }
        double change = data.path("f169").asDouble(0);
        double percent = data.path("f170").asDouble(0);
        long updatedAt = data.path("f86").asLong(0) * 1000L;
        if ("csi2000".equals(key)) {
            double volume = data.path("f47").asDouble(0) * 100; // 手 -> 股
            double amount = data.path("f48").asDouble(0);       // 元
            double ratio = data.path("f50").asDouble(0);        // 量比
            quotes.add(new MarketQuote(key, data.path("f58").asText("中证2000"),
                    price, change, percent, volume, amount, ratio, updatedAt));
        } else {
            quotes.add(new MarketQuote(key, data.path("f58").asText(overseasName(code)),
                    price, change, percent, 0, 0, data.path("f50").asDouble(0), updatedAt));
        }
        return quotes;
    }

    /** f58 缺失时的名称兜底，与 {@link EastMoneyClient} 的 secid 映射保持一致。 */
    private static String overseasName(String code) {
        return switch (code) {
            case "N225" -> "日经225";
            case "KS11" -> "韩国KOSPI";
            case "XIN9" -> "富时中国A50";
            default -> code;
        };
    }

    /** f104/f105/f106 分别对应上涨/下跌/平盘家数；按沪、深、北三市求和。 */
    static MarketBreadth parseBreadth(String json) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IOException("涨跌家数无数据");
        }
        JsonNode diff = MAPPER.readTree(json).path("data").path("diff");
        if (!diff.isArray()) {
            throw new IOException("涨跌家数无数据");
        }
        int up = 0;
        int down = 0;
        int flat = 0;
        for (JsonNode node : diff) {
            up += node.path("f104").asInt(0);
            down += node.path("f105").asInt(0);
            flat += node.path("f106").asInt(0);
        }
        MarketBreadth breadth = new MarketBreadth(up, down, flat);
        if (!breadth.isValid()) {
            throw new IOException("涨跌家数数据异常");
        }
        return breadth;
    }

    static double number(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
