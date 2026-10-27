package com.dp.agent.discover;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleRankerTest {

    @Test
    void keepsPerSourceLimitsAndOrder() {
        ArticleRanker.HotLists hot = ArticleRanker.hot(
                group("hn", "HN", 20, 100),
                group("hf", "HF", 20, 50),
                10);

        assertEquals(10, hot.hackerNews().size());
        assertEquals(10, hot.huggingFace().size());
        assertEquals("hn title 0", hot.hackerNews().get(0).title());
        assertEquals("hf title 0", hot.huggingFace().get(0).title());
    }

    @Test
    void deduplicatesSameTitleAcrossHotSources() {
        Article hn = article("hn", "HN", "Same Title", 100);
        Article hf = article("hf", "HF", "Same Title", 90);
        ArticleRanker.HotLists hot = ArticleRanker.hot(List.of(hn), List.of(hf), 10);

        assertEquals(1, hot.hackerNews().size());
        assertTrue(hot.huggingFace().isEmpty());
    }

    @Test
    void limitsChineseLatestList() {
        List<Article> latest = ArticleRanker.latest(group("cn", "CN", 30, 1), 20);
        assertEquals(20, latest.size());
        assertEquals("cn title 0", latest.get(0).title());
    }

    @Test
    void hotListsNeverExceedLimitWhenSourcesAreEmpty() {
        ArticleRanker.HotLists hot = ArticleRanker.hot(List.of(), List.of(), 10);
        assertTrue(hot.hackerNews().isEmpty());
        assertTrue(hot.huggingFace().isEmpty());
    }

    private static List<Article> group(String key, String source, int count, double base) {
        List<Article> articles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            articles.add(article(key, source, key + " title " + i, base - i));
        }
        return articles;
    }

    private static Article article(String key, String source, String title, double hotness) {
        return new Article(title, "https://example.com/" + title.replace(' ', '-'),
                source, key, "metric", System.currentTimeMillis(), hotness);
    }
}
