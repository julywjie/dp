package com.dp.agent.config;

/**
 * 内置预设的上下文窗口（tokens）。用户显式配置 &gt; 0 时以用户值为准。
 * 取值偏保守，留出输出与工具定义的余量。
 */
public final class ContextDefaults {

    public static final int FALLBACK = 8192;

    private ContextDefaults() {
    }

    public static int forProviderId(String id) {
        if (id == null) {
            return FALLBACK;
        }
        return switch (id) {
            case "kimi", "ollama" -> 8192;
            case "qwen" -> 32768;
            case "deepseek" -> 65536;
            case "glm" -> 131072;
            case "openai", "openrouter" -> 128000;
            case "anthropic" -> 200000;
            default -> FALLBACK;
        };
    }
}
