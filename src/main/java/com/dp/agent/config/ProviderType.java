package com.dp.agent.config;

/**
 * 提供商接入协议类型。
 * OPENAI_COMPATIBLE 覆盖所有 OpenAI 兼容端点（DeepSeek / 通义 / 智谱 / Kimi / OpenRouter / Ollama ...），
 * ANTHROPIC 走 Anthropic 原生协议。
 */
public enum ProviderType {
    OPENAI_COMPATIBLE("OpenAI 兼容"),
    ANTHROPIC("Anthropic");

    private final String label;

    ProviderType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
