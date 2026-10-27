package com.dp.agent.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Hugging Face 每日论文：按 upvotes + 评论数排序。 */
final class HuggingFaceSource implements NewsSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String label() {
        return "Hugging Face";
    }

    @Override
    public List<Article> fetch() throws Exception {
        JsonNode root = MAPPER.readTree(Http.get("https://huggingface.co/api/daily_papers?limit=50"));
        List<Article> articles = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode paper = node.path("paper");
            String title = paper.path("title").asText(node.path("title").asText("")).trim();
            String id = paper.path("id").asText("").trim();
            if (title.isEmpty() || id.isEmpty()) {
                continue;
            }
            int upvotes = node.path("upvotes").asInt(paper.path("upvotes").asInt(0));
            int comments = node.path("numComments").asInt(paper.path("numComments").asInt(0));
            long published = parseInstant(node.path("submittedOnDailyAt").asText(
                    node.path("publishedAt").asText("")));
            String metric = upvotes + " upvotes" + (comments > 0 ? " · " + comments + " 评论" : "");
            articles.add(new Article(title, "https://huggingface.co/papers/" + id,
                    "Hugging Face", "hf", metric, published, upvotes + comments * 2.0));
        }
        articles.sort(Comparator.comparingDouble(Article::hotness).reversed());
        return articles;
    }

    private long parseInstant(String text) {
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
