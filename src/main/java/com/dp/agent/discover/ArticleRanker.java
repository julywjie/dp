package com.dp.agent.discover;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 两条独立的榜单，不做跨来源分数比较：
 *   hot  —— Hacker News 与 Hugging Face 各自按真实热度取前 N，分开呈现；
 *   latest —— 中文 RSS 没有热度，只按发布时间取最新 N 条。
 */
public final class ArticleRanker {

    public static final int HOT_PER_SOURCE = 10;
    public static final int CHINESE_LIMIT = 20;

    public record HotLists(List<Article> hackerNews, List<Article> huggingFace) {
    }

    private ArticleRanker() {
    }

    public static HotLists hot(List<Article> hackerNews, List<Article> huggingFace, int perSource) {
        Set<String> seen = new HashSet<>();
        return new HotLists(take(hackerNews, perSource, seen), take(huggingFace, perSource, seen));
    }

    public static List<Article> latest(List<Article> chineseNews, int limit) {
        return take(chineseNews, limit, new HashSet<>());
    }

    private static List<Article> take(List<Article> articles, int limit, Set<String> seen) {
        List<Article> picked = new ArrayList<>();
        for (Article article : articles) {
            if (picked.size() >= limit) {
                break;
            }
            if (seen.add(dedupeKey(article))) {
                picked.add(article);
            }
        }
        return picked;
    }

    private static String dedupeKey(Article article) {
        String title = article.title() == null ? "" : article.title().toLowerCase(Locale.ROOT);
        title = title.replaceAll("[^\\p{L}\\p{N}]", "");
        return title.isEmpty() ? article.url().toLowerCase(Locale.ROOT) : title;
    }
}
