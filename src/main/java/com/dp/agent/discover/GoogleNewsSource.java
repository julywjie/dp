package com.dp.agent.discover;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Google News 中文「人工智能」RSS：作为中文内容补充。
 * RSS 没有互动量，热度只用时间衰减，不参与与 HN 分数的直接比较。
 */
final class GoogleNewsSource implements NewsSource {

    private static final int MAX_AGE_DAYS = 3;
    /** 过滤“AI 概念股/涨停”这类与阅读无关的行情噪音。 */
    private static final List<String> NOISE = List.of(
            "涨停", "跌停", "概念股", "个股", "股票", "A股", "港股", "美股",
            "收盘", "开盘", "涨幅", "跌幅", "资金流向", "ETF", "基金净值");

    @Override
    public String label() {
        return "Google News 中文";
    }

    @Override
    public List<Article> fetch() throws Exception {
        String query = URLEncoder.encode("人工智能", StandardCharsets.UTF_8);
        String url = "https://news.google.com/rss/search?q=" + query
                + "&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
        List<RssParser.Item> items = RssParser.parse(Http.get(url));

        long cutoff = Instant.now().minus(MAX_AGE_DAYS, ChronoUnit.DAYS).toEpochMilli();
        List<Article> articles = new ArrayList<>();
        for (RssParser.Item item : items) {
            if (item.publishedAt() < cutoff) {
                continue;
            }
            String title = stripSourceSuffix(item.title(), item.source());
            if (NOISE.stream().anyMatch(title::contains)) {
                continue;
            }
            String sourceName = item.source().isBlank() ? "中文媒体" : item.source();
            double ageHours = Math.max(0,
                    (System.currentTimeMillis() - item.publishedAt()) / 3_600_000.0);
            articles.add(new Article(title, item.link(), "中文媒体", "cn",
                    sourceName, item.publishedAt(), Math.exp(-ageHours / 72.0)));
        }
        articles.sort((a, b) -> Long.compare(b.publishedAt(), a.publishedAt()));
        return articles;
    }

    private static String stripSourceSuffix(String title, String source) {
        if (!source.isBlank() && title.endsWith(" - " + source)) {
            return title.substring(0, title.length() - source.length() - 3).trim();
        }
        return title;
    }
}
