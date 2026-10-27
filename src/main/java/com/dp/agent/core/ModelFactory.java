package com.dp.agent.core;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.config.ProviderType;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 模型工厂：按提供商配置创建阻塞式 ChatModel 或流式 StreamingChatModel。
 * 策略分派只有两类——OpenAI 兼容协议（覆盖绝大多数厂商）与 Anthropic 原生协议。
 *
 * 模型实例按「用途 + 提供商 + 端点 + 模型 + Key 指纹 + 参数」缓存，
 * 复用连接池与客户端；设置变更时由控制器调用 {@link #clearCache()}。
 */
public final class ModelFactory {

    private record CacheKey(String purpose, ProviderType type, String baseUrl,
                            String modelName, String keyFingerprint,
                            double temperature, int maxTokens, int timeoutSeconds) {
    }

    private static final int MAX_CACHE = 32;
    private static final Map<CacheKey, ChatModel> CHAT_CACHE = new ConcurrentHashMap<>();
    private static final Map<CacheKey, StreamingChatModel> STREAM_CACHE = new ConcurrentHashMap<>();

    private ModelFactory() {
    }

    public static ChatModel create(ProviderConfig cfg, String apiKey) {
        return chat("chat", cfg, apiKey, cfg.getTemperature(), cfg.getMaxTokens(),
                cfg.getTimeoutSeconds(), 1);
    }

    /** 摘要专用：短输出、短超时、不重试，避免压缩本身拖慢或放大费用。 */
    public static ChatModel createSummaryModel(ProviderConfig cfg, String apiKey) {
        int maxTokens = Math.min(512, Math.max(128, cfg.getMaxTokens()));
        int timeoutSeconds = Math.min(60, Math.max(15, cfg.getTimeoutSeconds()));
        return chat("summary", cfg, apiKey, 0.2, maxTokens, timeoutSeconds, 0);
    }

    /**
     * 发现页列表总结：推理型模型会先消耗隐藏的 reasoning token，
     * 若沿用摘要的 512 上限，正文很容易被截空；真实热榜标题更长，
     * reasoning 可能超过 2048，因此单独放宽到 4096。
     */
    public static ChatModel createListSummaryModel(ProviderConfig cfg, String apiKey) {
        int maxTokens = Math.min(4096, Math.max(2048, cfg.getMaxTokens()));
        int timeoutSeconds = Math.min(90, Math.max(30, cfg.getTimeoutSeconds()));
        return chat("list-summary", cfg, apiKey, 0.2, maxTokens, timeoutSeconds, 0);
    }

    /**
     * 单篇文章总结：需要输出 350–550 字的具体事实，推理型模型的 reasoning
     * 叠加正文理解后，4096 仍可能被截断，因此单独放宽（按上下文窗口封顶 8192）。
     */
    public static ChatModel createArticleSummaryModel(ProviderConfig cfg, String apiKey) {
        int maxTokens = Math.min(8192, Math.max(4096, cfg.effectiveContextWindow() / 4));
        int timeoutSeconds = Math.min(120, Math.max(60, cfg.getTimeoutSeconds()));
        return chat("article-summary", cfg, apiKey, 0.2, maxTokens, timeoutSeconds, 0);
    }

    /** 记忆提取：短输出、低温、不重试；失败不影响主对话。 */
    public static ChatModel createMemoryModel(ProviderConfig cfg, String apiKey) {
        int maxTokens = Math.min(400, Math.max(128, cfg.getMaxTokens()));
        int timeoutSeconds = Math.min(30, Math.max(10, cfg.getTimeoutSeconds()));
        return chat("memory", cfg, apiKey, 0.0, maxTokens, timeoutSeconds, 0);
    }

    public static StreamingChatModel createStreaming(ProviderConfig cfg, String apiKey) {
        CacheKey key = new CacheKey("stream", cfg.getType(), nullToEmpty(cfg.getBaseUrl()),
                nullToEmpty(cfg.getModelName()), fingerprint(apiKey),
                cfg.getTemperature(), cfg.getMaxTokens(), cfg.getTimeoutSeconds());
        if (STREAM_CACHE.size() > MAX_CACHE) {
            STREAM_CACHE.clear();
        }
        return STREAM_CACHE.computeIfAbsent(key, ignored -> buildStreaming(cfg, apiKey));
    }

    /** 设置保存后调用，释放旧端点/旧 Key 对应的客户端。 */
    public static void clearCache() {
        CHAT_CACHE.clear();
        STREAM_CACHE.clear();
    }

    private static ChatModel chat(String purpose, ProviderConfig cfg, String apiKey,
                                  double temperature, int maxTokens, int timeoutSeconds,
                                  int maxRetries) {
        CacheKey key = new CacheKey(purpose, cfg.getType(), nullToEmpty(cfg.getBaseUrl()),
                nullToEmpty(cfg.getModelName()), fingerprint(apiKey),
                temperature, maxTokens, timeoutSeconds);
        if (CHAT_CACHE.size() > MAX_CACHE) {
            CHAT_CACHE.clear();
        }
        Supplier<ChatModel> builder = () -> buildChat(cfg, apiKey, temperature,
                maxTokens, timeoutSeconds, maxRetries);
        return CHAT_CACHE.computeIfAbsent(key, ignored -> builder.get());
    }

    private static ChatModel buildChat(ProviderConfig cfg, String apiKey, double temperature,
                                       int maxTokens, int timeoutSeconds, int maxRetries) {
        if (cfg.getType() == ProviderType.ANTHROPIC) {
            return AnthropicChatModel.builder()
                    .baseUrl(normalizeAnthropicBaseUrl(cfg.getBaseUrl()))
                    .apiKey(apiKey)
                    .modelName(cfg.getModelName())
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .maxRetries(maxRetries)
                    .build();
        }
        return OpenAiChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(apiKey)
                .modelName(cfg.getModelName())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
    }

    private static StreamingChatModel buildStreaming(ProviderConfig cfg, String apiKey) {
        if (cfg.getType() == ProviderType.ANTHROPIC) {
            return AnthropicStreamingChatModel.builder()
                    .baseUrl(normalizeAnthropicBaseUrl(cfg.getBaseUrl()))
                    .apiKey(apiKey)
                    .modelName(cfg.getModelName())
                    .maxTokens(cfg.getMaxTokens())
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .build();
        }
        return OpenAiStreamingChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(apiKey)
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .maxTokens(cfg.getMaxTokens())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .build();
    }

    private static String fingerprint(String apiKey) {
        String value = apiKey == null ? "" : apiKey;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String nullToEmpty(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    /**
     * langchain4j 的 Anthropic 客户端约定 baseUrl 以 /v1 结尾（此后只追加 /messages），
     * 而各厂商文档（如智谱 open.bigmodel.cn/api/anthropic）遵循官方 Anthropic SDK
     * 生态约定：不带 /v1。此处自动补齐，两种写法都可用。
     */
    static String normalizeAnthropicBaseUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return u;
        }
        return trimTrailingSlash(u).endsWith("/v1") ? trimTrailingSlash(u) : trimTrailingSlash(u) + "/v1";
    }

    private static String trimTrailingSlash(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
