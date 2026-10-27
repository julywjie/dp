package com.dp.agent.discover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoverCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsBothLists() throws Exception {
        DiscoverCache cache = new DiscoverCache(tempDir);
        Article hn = article("HN 文章");
        Article hf = article("HF 论文");
        Article cn = article("中文资讯");
        cache.save(List.of(hn), List.of(hf), List.of(cn));

        DiscoverCache.Snapshot snapshot = cache.load().orElseThrow();
        assertEquals(1, snapshot.hotHackerNews().size());
        assertEquals(1, snapshot.hotHuggingFace().size());
        assertEquals(1, snapshot.chinese().size());
        assertEquals("中文资讯", snapshot.chinese().get(0).title());
        assertTrue(snapshot.fetchedAt() > 0);
    }

    @Test
    void corruptCacheIsIgnored() throws Exception {
        DiscoverCache cache = new DiscoverCache(tempDir);
        Files.createDirectories(cache.file().getParent());
        Files.writeString(cache.file(), "{ broken");

        assertTrue(cache.load().isEmpty());
    }

    private static Article article(String title) {
        return new Article(title, "https://example.com/" + title, "source", "hn",
                "100 分", System.currentTimeMillis(), 42.0);
    }
}
