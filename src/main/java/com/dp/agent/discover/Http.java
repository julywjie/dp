package com.dp.agent.discover;

import com.dp.agent.net.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 热榜抓取的统一 HTTP 客户端：短超时、跟随跳转、明确 User-Agent。 */
final class Http {

    private static final HttpClient CLIENT =
            HttpClients.create(Duration.ofSeconds(8), true);

    private Http() {
    }

    static String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "DP-Agent/1.0 (desktop; personal news reader)")
                .header("Accept", "application/json, application/rss+xml, application/xml, text/xml, */*")
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
