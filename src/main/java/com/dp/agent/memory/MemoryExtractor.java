package com.dp.agent.memory;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.core.ModelFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用模型从一轮对话中提取长期记忆。
 * 只接受 JSON 数组；过滤密钥等敏感内容；失败时返回空列表，不影响对话。
 */
public final class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ITEMS = 5;
    private static final int MAX_TEXT = 200;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9]{8,}|bearer\\s+[a-z0-9._\\-]{10,}"
                    + "|api[_\\s-]?key\\s*[:=]|password\\s*[:=]|密码\\s*[:=]|token\\s*[:=])");

    private MemoryExtractor() {
    }

    public static List<MemoryEntry> extract(ProviderConfig cfg, String apiKey,
                                            String userText, String answer,
                                            List<MemoryEntry> existing,
                                            String sessionId) throws Exception {
        String prompt = buildPrompt(userText, answer, existing);
        ChatModel model = ModelFactory.createMemoryModel(cfg, apiKey);
        String raw = model.chat(prompt);
        return parseResponse(raw, sessionId);
    }

    static List<MemoryEntry> parseResponse(String raw, String sessionId) {
        List<MemoryEntry> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String json = extractArray(raw);
        if (json == null) {
            return result;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return result;
            }
            for (JsonNode node : root) {
                if (result.size() >= MAX_ITEMS) {
                    break;
                }
                String text = node.path("text").asText("").strip();
                if (text.length() < 4 || SECRET.matcher(text).find()) {
                    continue;
                }
                if (text.length() > MAX_TEXT) {
                    text = text.substring(0, MAX_TEXT) + "…";
                }
                MemoryKind kind = MemoryKind.parse(node.path("kind").asText("FACT"));
                if (kind == MemoryKind.GENERAL) {
                    kind = MemoryKind.FACT; // 自动提取不生成全局记忆
                }
                int importance = Math.max(1, Math.min(5, node.path("importance").asInt(3)));
                result.add(MemoryEntry.auto(kind, text, importance, sessionId));
            }
        } catch (Exception e) {
            log.warn("记忆提取结果解析失败: {}", e.getMessage());
            return List.of();
        }
        return result;
    }

    /** 兼容模型输出前后带说明或代码围栏的情况。 */
    private static String extractArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String buildPrompt(String userText, String answer,
                                      List<MemoryEntry> existing) {
        StringBuilder prompt = new StringBuilder("""
                你是长期记忆提取器。从下面这轮对话中提取值得跨会话保留的信息。
                只输出 JSON 数组，最多 5 条；没有值得记住的内容就输出 []。
                格式：[{"kind":"PREFERENCE|FACT|PROJECT|DECISION","text":"...","importance":1}]
                规则：
                1. 只记录用户明确表达或对话中确认的事实，不要推测；
                2. 不保存密码、API Key、Token、验证码、临时数字；
                3. 不保存寒暄、模型自我介绍、临时调试信息；
                4. 每条不超过 100 字，必须是可独立理解的陈述。

                已有记忆（避免重复）：
                """);
        if (existing == null || existing.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (MemoryEntry entry : existing) {
                prompt.append("- ").append(abbreviate(entry.text(), 100)).append('\n');
            }
        }
        prompt.append("\n对话：\n用户：").append(abbreviate(userText, 1500))
                .append("\n助手：").append(abbreviate(answer, 2000));
        return prompt.toString();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
