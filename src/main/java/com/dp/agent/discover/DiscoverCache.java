package com.dp.agent.discover;

import com.dp.agent.storage.JsonFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 热榜磁盘缓存：热榜与中文资讯分开存储，启动先展示缓存。 */
public final class DiscoverCache {

    public static final Duration TTL = Duration.ofMinutes(30);

    public record Snapshot(long fetchedAt, List<Article> hotHackerNews,
                           List<Article> hotHuggingFace, List<Article> chinese) {
        public boolean isStale() {
            // 用绝对值：系统时钟回拨或缓存时间戳在未来时也不会被当成“永远新鲜”。
            return Math.abs(System.currentTimeMillis() - fetchedAt) > TTL.toMillis();
        }

        public boolean isEmpty() {
            return size(hotHackerNews) + size(hotHuggingFace) + size(chinese) == 0;
        }

        private static int size(List<Article> articles) {
            return articles == null ? 0 : articles.size();
        }
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DiscoverCache(Path dataDir) {
        this.file = dataDir.resolve("discover").resolve("hot.json");
    }

    public Optional<Snapshot> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Snapshot snapshot = mapper.readValue(file.toFile(), Snapshot.class);
            if (snapshot == null || snapshot.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(snapshot.fetchedAt(),
                    sanitize(snapshot.hotHackerNews()),
                    sanitize(snapshot.hotHuggingFace()),
                    sanitize(snapshot.chinese())));
        } catch (Exception e) {
            System.err.println("[dp-agent] 读取热榜缓存失败: " + e.getMessage());
            return Optional.empty();
        }
    }

    public void save(List<Article> hotHackerNews, List<Article> hotHuggingFace,
                     List<Article> chinese) throws IOException {
        JsonFiles.writeAtomically(file, mapper, new Snapshot(System.currentTimeMillis(),
                hotHackerNews, hotHuggingFace, chinese));
    }

    /** 缓存文件是本机可写数据：只接受字段完整、协议安全的条目。 */
    private static List<Article> sanitize(List<Article> articles) {
        if (articles == null) {
            return List.of();
        }
        List<Article> clean = new ArrayList<>();
        for (Article article : articles) {
            if (article != null
                    && article.title() != null && !article.title().isBlank()
                    && isHttp(article.url())) {
                clean.add(article);
            }
        }
        return clean;
    }

    /** 前缀判断而非 URI 解析：真实标题链接可能含未编码的中文。 */
    private static boolean isHttp(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public Path file() {
        return file;
    }
}
