package com.dp.agent.discover;

import com.dp.agent.net.HttpClients;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取文章正文并提取可读文本。
 *
 * 只允许 http/https，并拒绝 localhost / 内网 / 链路本地地址，避免热榜里的链接
 * 被用来访问本机或内网服务。正文用 JDK 自带的 HTML 解析器提取，不引入额外依赖；
 * 抓取失败由上层降级为“仅基于标题”的概括。
 */
final class ArticleContentFetcher {

    private static final int MAX_BYTES = 1_500_000;
    private static final int MAX_TEXT = 6_000;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern CHARSET = Pattern.compile(
            "charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESC_NAME_FIRST = Pattern.compile(
            "(?is)<meta[^>]+(?:name|property)\\s*=\\s*[\"']"
                    + "(?:description|og:description)[\"'][^>]+content\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern META_DESC_CONTENT_FIRST = Pattern.compile(
            "(?is)<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]+"
                    + "(?:name|property)\\s*=\\s*[\"'](?:description|og:description)[\"']");
    private static final Set<String> SKIP_TAGS = Set.of(
            "script", "style", "noscript", "svg", "nav", "header", "footer",
            "aside", "form", "button", "select", "textarea");
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre",
            "td", "dd", "div", "article", "section");

    private final HttpClient client = HttpClients.create(Duration.ofSeconds(10), false);
    private final GoogleNewsResolver googleNews = new GoogleNewsResolver();

    /** 抓取并提取正文；失败抛 IOException，由上层决定是否降级。 */
    String fetch(String url) throws IOException {
        URI current = URI.create(url.trim());
        if (GoogleNewsResolver.isGoogleNewsArticle(current)) {
            current = URI.create(googleNews.resolve(current.toString()));
        }
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            if (!isSafeHttpUrl(current.toString())) {
                throw new IOException("链接不是可访问的公网 http/https 地址");
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", HttpClients.BROWSER_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("文章抓取被中断", e);
            } catch (java.net.http.HttpTimeoutException e) {
                throw new IOException("正文页面请求超时", e);
            } catch (java.net.ConnectException e) {
                throw new IOException("正文站点连接超时或不可达", e);
            }
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("location").orElse("");
                    if (location.isBlank()) {
                        throw new IOException("重定向缺少 Location");
                    }
                    current = current.resolve(location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("正文站点返回 HTTP " + status);
                }
                String contentType = response.headers().firstValue("content-type").orElse("");
                if (contentType.toLowerCase(Locale.ROOT).contains("application/pdf")) {
                    throw new IOException("PDF 暂不支持正文提取");
                }
                byte[] bytes = readLimited(body, MAX_BYTES);
                return decode(contentType, bytes);
            }
        }
        throw new IOException("重定向次数过多");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1 && total < limit) {
            int take = Math.min(read, limit - total);
            output.write(buffer, 0, take);
            total += take;
        }
        return output.toByteArray();
    }

    private static String decode(String contentType, byte[] bytes) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("application/pdf")) {
            return "";
        }
        Charset charset = charsetFor(contentType, bytes);
        String raw = new String(bytes, charset);
        if (type.contains("html") || raw.stripLeading().startsWith("<")) {
            return extractText(raw);
        }
        return normalize(raw);
    }

    static String extractText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        // HTML5 容器标签（nav/header/footer/aside...）不在 JDK 解析器的已知标签表里，
        // 先整块移除，避免导航、页脚文字混进正文。
        String cleaned = html.replaceAll(
                "(?is)<(script|style|noscript|svg|nav|header|footer|aside|form|button|"
                        + "select|textarea|head)\\b[^>]*>.*?</\\1\\s*>", " ");
        StringBuilder out = new StringBuilder();
        try {
            ParserDelegator parser = new ParserDelegator();
            parser.parse(new StringReader(cleaned), new HTMLEditorKit.ParserCallback() {
                private int skipDepth;

                @Override
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    String name = tag.toString().toLowerCase(Locale.ROOT);
                    if (SKIP_TAGS.contains(name)) {
                        skipDepth++;
                    } else if (skipDepth == 0 && isBlock(tag)) {
                        out.append('\n');
                    }
                }

                @Override
                public void handleEndTag(HTML.Tag tag, int pos) {
                    String name = tag.toString().toLowerCase(Locale.ROOT);
                    if (SKIP_TAGS.contains(name)) {
                        skipDepth = Math.max(0, skipDepth - 1);
                    } else if (skipDepth == 0 && isBlock(tag)) {
                        out.append('\n');
                    }
                }

                @Override
                public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    if (tag == HTML.Tag.BR && skipDepth == 0) {
                        out.append('\n');
                    }
                }

                @Override
                public void handleText(char[] data, int pos) {
                    if (skipDepth == 0) {
                        out.append(data);
                    }
                }
            }, true);
        } catch (Exception e) {
            // 解析器遇到极端 HTML 时退回简单去标签，保证仍有可总结文本
            out.setLength(0);
            out.append(cleaned.replaceAll("(?is)<[^>]+>", " "));
        }
        String text = normalize(out.toString());
        String description = metaDescription(html);
        if (text.length() < 500 && description.length() >= 40
                && !text.contains(description)) {
            text = description + "\n" + text;
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private static String metaDescription(String html) {
        if (html == null) {
            return "";
        }
        Matcher first = META_DESC_NAME_FIRST.matcher(html);
        Matcher second = META_DESC_CONTENT_FIRST.matcher(html);
        String value = first.find() ? first.group(1)
                : second.find() ? second.group(1) : "";
        if (value.isBlank()) {
            return "";
        }
        return normalize(value.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">"));
    }

    private static boolean isBlock(HTML.Tag tag) {
        return BLOCK_TAGS.contains(tag.toString().toLowerCase(Locale.ROOT));
    }

    private static String normalize(String text) {
        return text.replace(' ', ' ')
                .replaceAll("[\\t\\x0B\\f\\r\\u00a0]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .strip();
    }

    private static Charset charsetFor(String contentType, byte[] bytes) {
        Matcher header = CHARSET.matcher(contentType == null ? "" : contentType);
        if (header.find()) {
            Charset charset = safeCharset(header.group(1));
            if (charset != null) {
                return charset;
            }
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 2048),
                StandardCharsets.ISO_8859_1);
        Matcher meta = CHARSET.matcher(head);
        if (meta.find()) {
            Charset charset = safeCharset(meta.group(1));
            if (charset != null) {
                return charset;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset safeCharset(String name) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isSafeHttpUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            if ("localhost".equals(normalized) || normalized.endsWith(".local")
                    || "::1".equals(normalized)) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(normalized);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
