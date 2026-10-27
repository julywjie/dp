package com.dp.agent.market;

import com.dp.agent.net.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 行情抓取编排：A 股/港股指数与美元离岸人民币（均为公开免费接口）。
 *
 * 数据源分工（单源失败不影响其余指标）：
 *   - 腾讯财经（新浪兜底）：上证指数、沪深300、恒生科技、创业板指、科创50
 *   - 东方财富（{@link EastMoneyClient}）：中证2000、富时中国A50 / 日经225 / 韩国KOSPI、沪深北涨跌家数
 *   - 新浪财经 / 东方财富：美元兑离岸人民币（{@link ForexSource}）
 *
 * 纯网络与解析编排，不依赖 JavaFX；失败抛 IOException 由 UI 层降级显示。
 */
public final class MarketService {

    /** 美元兑离岸人民币在行情列表中的稳定 key。 */
    public static final String USDCNH_KEY = "usdcnh";

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    private static final String TENCENT_URL =
            "https://qt.gtimg.cn/q=sh000001,sh000300,hkHSTECH,sz399006,sh000688";
    private static final String SINA_URL =
            "https://hq.sinajs.cn/list=s_sh000001,s_sh000300,hkHSTECH,s_sz399006,s_sh000688";
    /** 腾讯/新浪核心指数条数：上证、沪深300、恒生科技、创业板指、科创50。 */
    private static final int CORE_COUNT = 5;

    /** 腾讯 900ms 内没返回就先并行请求新浪，保证失败时最坏等待明显缩短。 */
    private static final long HEDGE_DELAY_MS = 900;
    private static final long FALLBACK_WAIT_MS = 3_500;

    private final HttpClient client = HttpClients.create(Duration.ofSeconds(5), true);
    /** 复用同一连接池；外汇有自己的源站与解析规则，单独成类。 */
    private final ForexSource forex = new ForexSource(client);
    /** 东方财富：中证2000 与涨跌家数，同样复用连接池。 */
    private final EastMoneyClient eastMoney = new EastMoneyClient(client);

    /**
     * 上证指数 / 沪深300 / 恒生科技：腾讯为主、新浪兜底。
     * 腾讯 900ms 内没给出完整结果就并行请求新浪；主源结果只用来补齐缺失项。
     */
    public List<MarketQuote> fetchCoreIndices() throws IOException {
        CompletableFuture<List<MarketQuote>> tencent = fetchTencentAsync();
        List<MarketQuote> primary = null;
        IOException failure = null;
        try {
            primary = tencent.get(HEDGE_DELAY_MS, TimeUnit.MILLISECONDS);
            if (primary.size() >= CORE_COUNT) {
                return primary;
            }
        } catch (TimeoutException e) {
            // 腾讯偏慢：继续走并行兜底
        } catch (ExecutionException e) {
            failure = asIOException(e.getCause());
            log.warn("腾讯行情失败，尝试新浪兜底: {}", failure.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("行情请求被中断", e);
        }

        List<MarketQuote> secondary = null;
        try {
            secondary = fetchSinaAsync().get(FALLBACK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("新浪行情超时");
        } catch (ExecutionException e) {
            failure = asIOException(e.getCause());
            log.warn("新浪兜底失败: {}", failure.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("行情请求被中断", e);
        }

        if (primary == null) {
            try {
                primary = tencent.getNow(null); // 并行期间腾讯可能已返回
            } catch (CompletionException e) {
                failure = asIOException(e.getCause());
            }
        }
        List<MarketQuote> merged = MarketParsers.mergeMissing(
                primary == null ? List.of() : primary,
                secondary == null ? List.of() : secondary);
        if (merged.isEmpty()) {
            throw failure != null ? failure : new IOException("核心指数无有效数据");
        }
        return merged;
    }

    /** 美元兑离岸人民币（USD/CNH）即期汇率：新浪为主、东方财富兜底。 */
    public MarketQuote fetchUsdCnh() throws IOException {
        return forex.fetch();
    }

    /** 中证2000：东方财富节点轮询（见 {@link EastMoneyClient}）。 */
    public List<MarketQuote> fetchCsi2000() throws IOException {
        return eastMoney.fetchCsi2000();
    }

    /** 富时中国A50 / 日经225 / 韩国KOSPI：东方财富按 secid 单查（见 {@link EastMoneyClient}）。 */
    public List<MarketQuote> fetchOverseasIndices() throws IOException {
        return eastMoney.fetchOverseasIndices();
    }

    /** 沪深北三市上涨/下跌/平盘家数；失败由 UI 决定显示“—”。 */
    public MarketBreadth fetchBreadth() throws IOException {
        return eastMoney.fetchBreadth();
    }

    private CompletableFuture<List<MarketQuote>> fetchTencentAsync() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TENCENT_URL))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", HttpClients.BROWSER_UA)
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    ensureOk(response);
                    List<MarketQuote> quotes =
                            MarketParsers.parseTencent(new String(response.body(), MarketParsers.GBK));
                    if (quotes.isEmpty()) {
                        throw new CompletionException(new IOException("腾讯行情无有效数据"));
                    }
                    return quotes;
                });
    }

    private CompletableFuture<List<MarketQuote>> fetchSinaAsync() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SINA_URL))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", HttpClients.BROWSER_UA)
                .header("Referer", "https://finance.sina.com.cn/")
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    ensureOk(response);
                    List<MarketQuote> quotes =
                            MarketParsers.parseSina(new String(response.body(), MarketParsers.GBK));
                    if (quotes.isEmpty()) {
                        throw new CompletionException(new IOException("新浪行情无有效数据"));
                    }
                    return quotes;
                });
    }

    private static void ensureOk(HttpResponse<?> response) {
        if (response.statusCode() != 200) {
            throw new CompletionException(new HttpStatusException(response.statusCode()));
        }
    }

    private static IOException asIOException(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException(cause == null ? "行情请求失败" : cause.getMessage(), cause);
    }
}
