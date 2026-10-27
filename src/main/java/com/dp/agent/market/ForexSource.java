package com.dp.agent.market;

import com.dp.agent.net.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美元兑离岸人民币（USD/CNH）即期汇率。
 *
 * 数据源分工：
 *   - 新浪财经：主源，返回最新价、涨跌额、涨跌幅与日期时间（GBK）；
 *   - 东方财富：兜底，按「上次成功节点」优先轮询 push2 / push2test。
 *
 * 外汇没有成交量与量比，volume/amount/volumeRatio 保持 0，由 UI 按汇率样式展示。
 * 纯网络与解析，不依赖 JavaFX；失败抛 IOException 由 UI 标记为离线或沿用上一份数据。
 */
final class ForexSource {

    private static final Logger log = LoggerFactory.getLogger(ForexSource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Charset GBK = Charset.forName("GB18030");
    private static final Pattern SINA_LINE =
            Pattern.compile("hq_str_([A-Za-z0-9_]+)=\"([^\"]*)\"");

    static final String KEY = MarketService.USDCNH_KEY;
    static final String NAME = "美元离岸人民币";

    private static final String SINA_URL = "https://hq.sinajs.cn/list=fx_susdcnh";
    /** push2 对缺少 ut 的请求返回空 body；该 token 是东财公开接口的通用参数。 */
    private static final String EASTMONEY_UT = "&ut=fa5fd1943c7b386f172d6893dbfba10b";
    private static final String EASTMONEY_URL =
            "https://push2.eastmoney.com/api/qt/stock/get?secid=133.USDCNH"
                    + "&fields=f43,f57,f58,f86,f169,f170&fltt=2&invt=2" + EASTMONEY_UT;
    private static final String EASTMONEY_TEST_URL =
            "https://push2test.eastmoney.com/api/qt/stock/get?secid=133.USDCNH"
                    + "&fields=f43,f57,f58,f86,f169,f170&fltt=2&invt=2" + EASTMONEY_UT;
    private static final List<String> EASTMONEY_HOSTS =
            List.of(EASTMONEY_URL, EASTMONEY_TEST_URL);

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS_SINA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient client;
    /** 上次成功的东财节点；持续保留，避免每个周期先撞失效节点。 */
    private volatile int eastmoneyHost;

    ForexSource(HttpClient client) {
        this.client = client;
    }

    /** 新浪优先，失败后切换东方财富节点。失败抛 IOException。 */
    MarketQuote fetch() throws IOException {
        IOException failure = null;
        try {
            return fetchSina();
        } catch (IOException e) {
            failure = e;
            log.warn("新浪离岸人民币失败，尝试东方财富兜底: {}", e.getMessage());
        }
        for (int i = 0; i < EASTMONEY_HOSTS.size(); i++) {
            int index = Math.floorMod(eastmoneyHost + i, EASTMONEY_HOSTS.size());
            String url = EASTMONEY_HOSTS.get(index);
            try {
                MarketQuote quote = fetchEastmoney(url);
                eastmoneyHost = index;
                return quote;
            } catch (IOException e) {
                failure = e;
                log.warn("离岸人民币节点 {} 失败: {}",
                        HttpStatusException.hostOf(url), e.getMessage());
                if (HttpStatusException.isRateLimited(e)) {
                    break;
                }
            }
        }
        throw failure != null ? failure : new IOException("离岸人民币无有效数据");
    }

    private MarketQuote fetchSina() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SINA_URL))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", HttpClients.BROWSER_UA)
                .header("Referer", "https://finance.sina.com.cn/")
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request);
        return parseSina(new String(response.body(), GBK))
                .orElseThrow(() -> new IOException("新浪离岸人民币无有效数据"));
    }

    private MarketQuote fetchEastmoney(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", HttpClients.BROWSER_UA)
                .header("Referer", "https://quote.eastmoney.com/")
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request);
        return parseEastmoney(new String(response.body(), StandardCharsets.UTF_8))
                .orElseThrow(() -> new IOException("东方财富离岸人民币无有效数据"));
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            HttpResponse<byte[]> response = client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new HttpStatusException(response.statusCode());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("汇率请求被中断", e);
        }
    }

    /**
     * 新浪外汇字段（以 fx_susdcnh 实测为准）：
     *   0 时间 / 1 买入 / 2 卖出 / 3 昨收 / 4 成交量 / 5 今开 / 6 最高 / 7 最低 /
     *   8 最新价 / 9 名称 / 10 涨跌幅(%) / 11 涨跌额 / ... / 17 日期。
     */
    static Optional<MarketQuote> parseSina(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = SINA_LINE.matcher(text);
        while (matcher.find()) {
            if (!"fx_susdcnh".equalsIgnoreCase(matcher.group(1))) {
                continue;
            }
            String[] parts = matcher.group(2).split(",", -1);
            if (parts.length < 18) {
                continue;
            }
            double price = number(parts[8]);
            if (price <= 0) {
                continue;
            }
            return Optional.of(new MarketQuote(KEY, NAME, price,
                    number(parts[11]), number(parts[10]), 0, 0, 0,
                    parseSinaTime(parts[17], parts[0])));
        }
        return Optional.empty();
    }

    /** 东方财富 stock/get：fltt=2 已换算为小数，f43 最新价 / f169 涨跌额 / f170 涨跌幅。 */
    static Optional<MarketQuote> parseEastmoney(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode data = MAPPER.readTree(json).path("data");
        if (!data.isObject()) {
            return Optional.empty();
        }
        String code = data.path("f57").asText("");
        double price = data.path("f43").asDouble(0);
        if (!"USDCNH".equalsIgnoreCase(code) || price <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MarketQuote(KEY, NAME, price,
                data.path("f169").asDouble(0), data.path("f170").asDouble(0),
                0, 0, 0, data.path("f86").asLong(0) * 1000L));
    }

    private static long parseSinaTime(String date, String time) {
        try {
            String day = date.trim();
            String clock = time.trim();
            if (!day.matches("\\d{4}-\\d{2}-\\d{2}") || !clock.matches("\\d{2}:\\d{2}:\\d{2}")) {
                return 0;
            }
            return LocalDateTime.parse(day + " " + clock, TS_SINA)
                    .atZone(MARKET_ZONE).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
