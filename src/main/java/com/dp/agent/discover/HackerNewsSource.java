package com.dp.agent.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Hacker News 近 7 天 AI 相关热帖（Algolia 公开 API）。
 * 热度 = (points + 2×comments) × 时间衰减；标题必须命中 AI 关键词，过滤 "Airfoil" 之类误匹配。
 */
final class HackerNewsSource implements NewsSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern AI_TITLE = Pattern.compile(
            "(?i)\\b(AI|LLM|LLMs|GPT|ChatGPT|Claude|Gemini|OpenAI|Anthropic|DeepMind|"
                    + "machine learning|neural|transformer|agentic|agents?|diffusion|RAG|multimodal)\\b");
    private static final int MIN_POINTS = 30;

    @Override
    public String label() {
        return "Hacker News";
    }

    @Override
    public List<Article> fetch() throws Exception {
        long since = Instant.now().minus(7, ChronoUnit.DAYS).getEpochSecond();
        String filters = URLEncoder.encode("created_at_i>" + since, StandardCharsets.UTF_8);
        String url = "https://hn.algolia.com/api/v1/search?query=AI&tags=story&hitsPerPage=100"
                + "&numericFilters=" + filters;
        JsonNode root = MAPPER.readTree(Http.get(url));

        List<Article> articles = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            String title = hit.path("title").asText("").trim();
            int points = hit.path("points").asInt();
            if (title.isEmpty() || points < MIN_POINTS || !AI_TITLE.matcher(title).find()) {
                continue;
            }
            int comments = hit.path("num_comments").asInt();
            String link = hit.path("url").asText("").trim();
            if (link.isEmpty()) {
                link = "https://news.ycombinator.com/item?id=" + hit.path("objectID").asText();
            }
            long created = hit.path("created_at_i").asLong() * 1000L;
            double ageDays = Math.max(0, (System.currentTimeMillis() - created) / 86_400_000.0);
            double hotness = (points + comments * 2.0) * Math.exp(-ageDays / 7.0);
            articles.add(new Article(title, link, "Hacker News", "hn",
                    points + " 分 · " + comments + " 评论", created, hotness));
        }
        articles.sort(Comparator.comparingDouble(Article::hotness).reversed());
        return articles;
    }
}
