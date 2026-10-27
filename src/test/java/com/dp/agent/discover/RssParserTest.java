package com.dp.agent.discover;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssParserTest {

    @Test
    void parsesRssItems() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>test</title>
                  <item>
                    <title>某 AI 模型发布 - 示例媒体</title>
                    <link>https://example.com/news/1</link>
                    <pubDate>Sun, 18 Oct 2026 13:06:00 GMT</pubDate>
                    <source url="https://example.com">示例媒体</source>
                  </item>
                </channel></rss>
                """;
        List<RssParser.Item> items = RssParser.parse(xml);
        assertEquals(1, items.size());
        assertEquals("某 AI 模型发布 - 示例媒体", items.get(0).title());
        assertEquals("示例媒体", items.get(0).source());
        assertTrue(items.get(0).publishedAt() > 0);
    }

    @Test
    void rejectsDoctype() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]>
                <rss><channel><item><title>&xxe;</title><link>x</link></item></channel></rss>
                """;
        assertThrows(Exception.class, () -> RssParser.parse(xml));
    }
}
