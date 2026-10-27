package com.dp.agent.net;

import java.net.http.HttpClient;
import java.time.Duration;

/** 应用内 HTTP 客户端的统一构造入口：统一协议版本、重定向与超时语义。 */
public final class HttpClients {

    /** 行情/正文站点常用的浏览器 User-Agent。 */
    public static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    private HttpClients() {
    }

    public static HttpClient create(Duration connectTimeout, boolean followRedirects) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(followRedirects
                        ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1) // 东财等站点的 HTTP/2 嗅探不稳定
                .build();
    }
}
