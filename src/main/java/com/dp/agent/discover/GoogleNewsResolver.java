package com.dp.agent.discover;

import com.dp.agent.net.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Google News 的包装链接解析成真正的发布方 URL。
 *
 * Google News RSS 的 /rss/articles/CBMi... 不是文章地址，而是需要
 * batchexecute(Fbv4je) RPC 才能解析的跳转壳；直接抓壳只能拿到 JS 页面。
 * 解析失败时由上层降级为标题概括。
 */
final class GoogleNewsResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ARTICLE_ID = Pattern.compile("data-n-a-id=\"([^\"]+)\"");
    private static final Pattern TIMESTAMP = Pattern.compile("data-n-a-ts=\"([^\"]+)\"");
    private static final Pattern SIGNATURE = Pattern.compile("data-n-a-sg=\"([^\"]+)\"");
    private static final String COOKIE = "CONSENT=YES+; SOCS=CAI";

    private final HttpClient client = HttpClients.create(Duration.ofSeconds(10), true);

    static boolean isGoogleNewsArticle(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getPath() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        String path = uri.getPath();
        return host.equals("news.google.com")
                && (path.startsWith("/rss/articles/") || path.startsWith("/articles/"));
    }

    /** 返回发布方 URL；无法解析时抛 IOException，由调用方降级。 */
    String resolve(String articleUrl) throws IOException {
        URI uri = URI.create(articleUrl.trim());
        if (!isGoogleNewsArticle(uri)) {
            return articleUrl;
        }
        String html = get(articleUrl, URI.create("https://news.google.com/"));
        String id = match(ARTICLE_ID, html);
        String timestamp = match(TIMESTAMP, html);
        String signature = match(SIGNATURE, html);
        if (id == null || timestamp == null || signature == null) {
            throw new IOException("Google News 页面缺少跳转参数");
        }
        String response = postBatch(id, timestamp, signature, uri);
        String publisher = parseResolved(response);
        if (publisher == null || publisher.isBlank()) {
            throw new IOException("Google News 未返回原文地址");
        }
        if (!ArticleContentFetcher.isSafeHttpUrl(publisher)) {
            throw new IOException("Google News 解析出的原文地址不安全");
        }
        return publisher;
    }

    private String get(String url, URI referer) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", HttpClients.BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .header("Cookie", COOKIE)
                .GET()
                .build();
        return send(request);
    }

    private String postBatch(String id, String timestamp, String signature, URI article)
            throws IOException {
        try {
            List<Object> config = Arrays.asList(
                    "X", "X", Arrays.asList("X", "X"), null, null, 1, 1, "US:en",
                    null, 1, null, null, null, null, null, 0, 1);
            List<Object> garturlreq = Arrays.asList(
                    "garturlreq",
                    Arrays.asList(config, "X", "X", 1, Arrays.asList(1, 1, 1),
                            1, 1, null, 0, 0, null, 0),
                    id, Long.parseLong(timestamp), signature);
            String inner = MAPPER.writeValueAsString(garturlreq);
            List<Object> call = Arrays.asList("Fbv4je", inner, null, "generic");
            String freq = MAPPER.writeValueAsString(
                    java.util.Collections.singletonList(
                            java.util.Collections.singletonList(call)));

            String sourcePath = article.getPath();
            String endpoint = "https://news.google.com/_/DotsSplashUi/data/batchexecute"
                    + "?rpcids=Fbv4je&source-path=" + sourcePath
                    + "&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
            String body = "f.req=" + URLEncoder.encode(freq, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", HttpClients.BROWSER_UA)
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .header("Cookie", COOKIE)
                    .header("Origin", "https://news.google.com")
                    .header("Referer", article.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return send(request);
        } catch (NumberFormatException e) {
            throw new IOException("Google News 时间戳格式异常", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Google News 请求构造失败", e);
        }
    }

    private String send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Google News HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Google News 请求被中断", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("Google News 请求超时", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("Google News 连接超时或不可达", e);
        }
    }

    /** 解析 batchexecute 响应；独立出来便于用固定样本做单测。 */
    static String parseResolved(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        int start = response.indexOf('[');
        if (start < 0) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(response.substring(start));
            if (!root.isArray()) {
                return null;
            }
            for (JsonNode row : root) {
                if (!row.isArray() || row.size() < 3) {
                    continue;
                }
                if (!"Fbv4je".equals(row.get(1).asText())) {
                    continue;
                }
                String payload = row.get(2).asText();
                JsonNode value = MAPPER.readTree(payload);
                if (value.isArray() && value.size() >= 2
                        && "garturlres".equals(value.get(0).asText())) {
                    return value.get(1).asText();
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String match(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
