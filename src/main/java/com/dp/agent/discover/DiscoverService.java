package com.dp.agent.discover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 热榜聚合门面：并发抓取三个来源，单源失败不影响其他源；结果排序后写缓存。
 * 所有网络调用都发生在守护线程池中，不阻塞 JavaFX 线程。
 *
 * 缓存语义：部分来源失败时沿用上一份成功结果，绝不用空列表覆盖好数据；
 * 只有至少一个来源成功才写盘。
 */
public final class DiscoverService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiscoverService.class);

    public record RefreshResult(List<Article> hotHackerNews, List<Article> hotHuggingFace,
                                List<Article> chinese, long fetchedAt, List<String> failedSources) {
    }

    private record SourceResult(NewsSource source, List<Article> articles, boolean ok) {
        List<Article> orFallback(List<Article> fallback) {
            return ok ? articles : (fallback == null ? List.of() : fallback);
        }
    }

    private final DiscoverCache cache;
    private final NewsSource hackerNews = new HackerNewsSource();
    private final NewsSource huggingFace = new HuggingFaceSource();
    private final NewsSource chineseNews = new GoogleNewsSource();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "discover-fetch");
        thread.setDaemon(true);
        return thread;
    });

    public DiscoverService(Path dataDir) {
        this.cache = new DiscoverCache(dataDir);
    }

    public Optional<DiscoverCache.Snapshot> cached() {
        return cache.load();
    }

    /** 缓存读取放在抓取线程池里，避免在 FX 线程读盘。 */
    public CompletableFuture<Optional<DiscoverCache.Snapshot>> cachedAsync() {
        return CompletableFuture.supplyAsync(cache::load, executor);
    }

    public CompletableFuture<RefreshResult> refresh() {
        // 读旧缓存放到后台：refresh() 本身在 FX 线程调用。
        return CompletableFuture.supplyAsync(cache::load, executor)
                .thenCompose(previous -> {
                    CompletableFuture<SourceResult> hn = fetch(hackerNews);
                    CompletableFuture<SourceResult> hf = fetch(huggingFace);
                    CompletableFuture<SourceResult> cn = fetch(chineseNews);
                    return CompletableFuture.allOf(hn, hf, cn)
                            .thenApply(ignored -> combine(previous.orElse(null), hn.join(),
                                    hf.join(), cn.join()));
                });
    }

    private RefreshResult combine(DiscoverCache.Snapshot previous,
                                  SourceResult hn, SourceResult hf, SourceResult cn) {
        List<Article> oldHn = previous == null ? null : previous.hotHackerNews();
        List<Article> oldHf = previous == null ? null : previous.hotHuggingFace();
        List<Article> oldCn = previous == null ? null : previous.chinese();

        ArticleRanker.HotLists hot = ArticleRanker.hot(
                hn.orFallback(oldHn), hf.orFallback(oldHf), ArticleRanker.HOT_PER_SOURCE);
        List<Article> chinese = ArticleRanker.latest(cn.orFallback(oldCn),
                ArticleRanker.CHINESE_LIMIT);

        List<String> failed = new ArrayList<>();
        addFailure(failed, hn, oldHn);
        addFailure(failed, hf, oldHf);
        addFailure(failed, cn, oldCn);

        boolean anySuccess = hn.ok() || hf.ok() || cn.ok();
        if (anySuccess) {
            try {
                cache.save(hot.hackerNews(), hot.huggingFace(), chinese);
            } catch (IOException e) {
                log.warn("热榜缓存写入失败", e);
            }
        }
        return new RefreshResult(hot.hackerNews(), hot.huggingFace(), chinese,
                System.currentTimeMillis(), List.copyOf(failed));
    }

    private void addFailure(List<String> failed, SourceResult result, List<Article> fallback) {
        if (result.ok()) {
            return;
        }
        boolean reused = fallback != null && !fallback.isEmpty();
        failed.add(result.source().label() + (reused ? "（沿用上次结果）" : ""));
    }

    private CompletableFuture<SourceResult> fetch(NewsSource source) {
        return CompletableFuture.supplyAsync(() -> {
            Exception last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return new SourceResult(source, source.fetch(), true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    last = e;
                    break;
                } catch (Exception e) {
                    last = e;
                    if (attempt == 0) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            log.warn("热榜来源失败 {}: {}", source.label(),
                    last == null ? "unknown" : last.getMessage());
            return new SourceResult(source, List.of(), false);
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
