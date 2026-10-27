package com.dp.agent.discover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleContentFetcherTest {

    @Test
    void extractsReadableTextAndSkipsNoise() {
        String html = "<html><head><style>.x{color:red}</style>"
                + "<script>alert('bad')</script></head><body>"
                + "<nav>导航菜单</nav>"
                + "<article><h1>文章标题</h1><p>第一段内容。</p><p>第二段内容。</p></article>"
                + "<footer>页脚信息</footer></body></html>";

        String text = ArticleContentFetcher.extractText(html);

        assertTrue(text.contains("文章标题"));
        assertTrue(text.contains("第一段内容"));
        assertTrue(text.contains("第二段内容"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("导航菜单"));
        assertFalse(text.contains("页脚信息"));
    }

    @Test
    void usesMetaDescriptionWhenBodyIsThin() {
        String html = "<html><head><meta name=\"description\" content=\""
                + "这是一段四十个字以上的页面描述，用于在正文很少时补充文章的核心信息，避免总结过于空泛。"
                + "\"></head><body><p>短正文。</p></body></html>";

        String text = ArticleContentFetcher.extractText(html);

        assertTrue(text.contains("这是一段四十个字以上的页面描述"));
        assertTrue(text.contains("短正文"));
    }

    @Test
    void rejectsLocalPrivateAndNonHttpUrls() {
        assertFalse(ArticleContentFetcher.isSafeHttpUrl("http://localhost:8080/a"));
        assertFalse(ArticleContentFetcher.isSafeHttpUrl("http://127.0.0.1/a"));
        assertFalse(ArticleContentFetcher.isSafeHttpUrl("http://192.168.1.1/a"));
        assertFalse(ArticleContentFetcher.isSafeHttpUrl("http://[::1]/a"));
        assertFalse(ArticleContentFetcher.isSafeHttpUrl("file:///etc/passwd"));
        assertFalse(ArticleContentFetcher.isSafeHttpUrl("ftp://example.com/a"));
    }
}
