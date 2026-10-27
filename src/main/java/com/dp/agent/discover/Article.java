package com.dp.agent.discover;

/** 热榜条目：来源、热度指标与发布时间都已整理好，UI 不再关心抓取细节。 */
public record Article(String title, String url, String source, String sourceKey,
                      String metric, long publishedAt, double hotness) {
}
