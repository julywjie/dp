package com.dp.agent.discover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleSummarizerTest {

    private static final Article ARTICLE = new Article("A new model for agents",
            "https://example.com/a", "Hacker News", "hn", "points 120", 0, 1);

    @Test
    void promptUsesBodyWhenAvailable() {
        String prompt = ArticleSummarizer.buildPrompt(ARTICLE,
                "这是文章正文，介绍了一个新的智能体模型和评测结果。", true);

        assertTrue(prompt.contains("A new model for agents"));
        assertTrue(prompt.contains("这是文章正文"));
        assertTrue(prompt.contains("正文："));
        assertTrue(prompt.contains("不得编造"));
        assertTrue(prompt.contains("**关键信息**"));
        assertTrue(prompt.contains("4–6"));
        assertTrue(prompt.contains("至少保留 3 个"));
        assertTrue(prompt.contains("350–550"));
    }

    @Test
    void promptFallsBackToTitleWhenBodyUnavailable() {
        String prompt = ArticleSummarizer.buildPrompt(ARTICLE, "", false);

        assertTrue(prompt.contains("只能看到标题"));
        assertTrue(prompt.contains("A new model for agents"));
        assertTrue(prompt.contains("**不确定性**"));
        assertTrue(prompt.contains("从标题看"));
        assertFalse(prompt.contains("正文："));
    }
}
