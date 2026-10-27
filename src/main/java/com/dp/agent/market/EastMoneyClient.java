package com.dp.agent.market;

import com.dp.agent.net.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 东方财富行情客户端：中证2000、富时中国A50 / 日经225 / 韩国KOSPI（stock/get 单指数查询）
 * 与沪深北涨跌家数（ulist 批量）。腾讯/新浪不提供这几个品种的稳定代码。
 *
 * 东方财富同一份数据分布在多个边缘域名上：push2/push2his 在部分网络（代理/TUN）下
 * 会被断连；push2test 是可用的镜像节点。按「上次成功节点」优先轮询，失败再切换，
 * 避免每个周期都先撞坏节点。复用调用方的连接池；失败抛 IOException。
 */
final class EastMoneyClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyClient.class);

    /** 中证2000 的 stock/get secid。 */
    private static final String CSI_SECID = "2.932000";
    /** 海外/跨境指数 secid → 行情名称；ulist 对全球指数只返回空值，只能逐个单查。 */
    private static final Map<String, String> OVERSEAS_SECIDS =
            Map.of("100.N225", "日经225", "100.KS11", "韩国KOSPI",
                    "100.XIN9", "富时中国A50");

    /** 沪深北三市涨跌家数；f104/f105/f106 分别为上涨/下跌/平盘家数。 */
    private static final String BREADTH_FIELDS =
            "secids=1.000001,0.399001,0.899050&fields=f104,f105,f106,f12,f14";
    private static final List<String> BREADTH_HOSTS = List.of(
            "https://push2test.eastmoney.com/api/qt/ulist.np/get?" + BREADTH_FIELDS,
            "https://push2his.eastmoney.com/api/qt/ulist.np/get?" + BREADTH_FIELDS,
            "https://push2.eastmoney.com/api/qt/ulist.np/get?" + BREADTH_FIELDS);

    private final HttpClient client;

    /** stock/get 家族（中证2000 / 海外指数）共享的节点偏好；同族节点可用性一致。 */
    private final NodeSlot stockNode = new NodeSlot();
    private final NodeSlot breadthNode = new NodeSlot();

    EastMoneyClient(HttpClient client) {
        this.client = client;
    }

    /** 中证2000：按上次成功节点优先轮询，失败自动换节点。 */
    List<MarketQuote> fetchCsi2000() throws IOException {
        return rotate(stockGetUrls(CSI_SECID), stockNode, "中证2000", this::fetchStockGet);
    }

    /** 富时中国A50 / 日经225 / 韩国KOSPI：逐个单查；单指数失败不影响其余，全部失败才抛。 */
    List<MarketQuote> fetchOverseasIndices() throws IOException {
        List<MarketQuote> quotes = new ArrayList<>();
        IOException failure = null;
        for (Map.Entry<String, String> entry : OVERSEAS_SECIDS.entrySet()) {
            try {
                quotes.addAll(rotate(stockGetUrls(entry.getKey()),
                        stockNode, entry.getValue(), this::fetchStockGet));
            } catch (IOException e) {
                failure = e;
            }
        }
        if (quotes.isEmpty()) {
            throw failure != null ? failure : new IOException("海外指数无有效数据");
        }
        return quotes;
    }

    /** 沪深北三市上涨/下跌/平盘家数；失败抛 IOException，由 UI 决定显示“—”。 */
    MarketBreadth fetchBreadth() throws IOException {
        return rotate(BREADTH_HOSTS, breadthNode, "涨跌家数", this::fetchBreadth);
    }

    /** 从上次成功节点开始轮询；全部失败抛最后一次 IOException。 */
    private <T> T rotate(List<String> urls, NodeSlot slot, String what,
                         UrlFetcher<T> fetcher) throws IOException {
        IOException failure = null;
        for (int i = 0; i < urls.size(); i++) {
            int index = Math.floorMod(slot.preferred + i, urls.size());
            String url = urls.get(index);
            try {
                T result = fetcher.fetch(url);
                slot.preferred = index;
                return result;
            } catch (IOException e) {
                failure = e;
                log.warn("{} 节点 {} 失败: {}", what, HttpStatusException.hostOf(url), e.getMessage());
                if (HttpStatusException.isRateLimited(e)) {
                    break;
                }
            }
        }
        throw failure != null ? failure : new IOException(what + "无有效数据");
    }

    @FunctionalInterface
    private interface UrlFetcher<T> {
        T fetch(String url) throws IOException;
    }

    /** 节点偏好槽：记录上次成功的下标，跨刷新周期优先打可用节点。 */
    private static final class NodeSlot {
        volatile int preferred;
    }

    /**
     * stock/get 节点顺序：push2his / push2 数据更全（push2test 的中证2000 量比
     * 恒为 "-"），因此官方节点优先，push2test 仅作镜像兜底；断连通常立即失败，
     * 坏网络下多撞两个节点的代价可忽略，且节点偏好会记住最后成功的节点。
     */
    private static List<String> stockGetUrls(String secid) {
        String query = "secid=" + secid
                + "&fields=f43,f47,f48,f50,f57,f58,f86,f169,f170&fltt=2&invt=2";
        return List.of(
                "https://push2his.eastmoney.com/api/qt/stock/get?" + query,
                "https://push2.eastmoney.com/api/qt/stock/get?" + query,
                "https://push2test.eastmoney.com/api/qt/stock/get?" + query);
    }

    private List<MarketQuote> fetchStockGet(String url) throws IOException {
        HttpResponse<byte[]> response = send(url);
        List<MarketQuote> quotes = MarketParsers.parseEastmoney(
                new String(response.body(), StandardCharsets.UTF_8));
        if (quotes.isEmpty()) {
            throw new IOException("东方财富行情无有效数据");
        }
        return quotes;
    }

    private MarketBreadth fetchBreadth(String url) throws IOException {
        HttpResponse<byte[]> response = send(url);
        return MarketParsers.parseBreadth(
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> send(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", HttpClients.BROWSER_UA)
                .header("Referer", "https://quote.eastmoney.com/")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new HttpStatusException(response.statusCode());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("行情请求被中断", e);
        }
    }
}
