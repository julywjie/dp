package com.dp.agent.config;

import java.util.UUID;

/**
 * 单个模型提供商的配置。apiKeyEnc 为 AES-GCM 加密后的密文（Base64），
 * 明文 Key 只在创建模型实例的瞬间解密使用，不落地。
 */
public class ProviderConfig {

    private String id = UUID.randomUUID().toString();
    private String displayName = "";
    private ProviderType type = ProviderType.OPENAI_COMPATIBLE;
    private String baseUrl = "";
    private String apiKeyEnc = "";
    private String modelName = "";
    private double temperature = 0.7;
    private int maxTokens = 2048;
    private int timeoutSeconds = 120;
    /** 0 = 未显式配置，按预设 id 取默认值（兼容旧 config.json）。 */
    private int contextWindow = 0;

    /** 便捷构造：用于预设。 */
    public static ProviderConfig of(String id, String displayName, ProviderType type,
                                    String baseUrl, String modelName) {
        ProviderConfig c = new ProviderConfig();
        c.id = id;
        c.displayName = displayName;
        c.type = type;
        c.baseUrl = baseUrl;
        c.modelName = modelName;
        return c;
    }

    public boolean hasApiKey() {
        return apiKeyEnc != null && !apiKeyEnc.isBlank();
    }

    /** 深拷贝：设置对话框在草稿上编辑，取消时不影响运行中的配置。 */
    public ProviderConfig copy() {
        ProviderConfig c = new ProviderConfig();
        c.id = id;
        c.displayName = displayName;
        c.type = type;
        c.baseUrl = baseUrl;
        c.apiKeyEnc = apiKeyEnc;
        c.modelName = modelName;
        c.temperature = temperature;
        c.maxTokens = maxTokens;
        c.timeoutSeconds = timeoutSeconds;
        c.contextWindow = contextWindow;
        return c;
    }

    @Override
    public String toString() {
        String key = hasApiKey() ? "" : "（未配 Key）";
        return displayName + " · " + modelName + key;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public ProviderType getType() { return type; }
    public void setType(ProviderType type) { this.type = type; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    /** 实际使用的上下文窗口：显式值优先，否则按预设 id 推断。 */
    public int effectiveContextWindow() {
        return contextWindow > 0 ? contextWindow : ContextDefaults.forProviderId(id);
    }
}
