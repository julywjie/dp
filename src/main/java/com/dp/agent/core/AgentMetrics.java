package com.dp.agent.core;

/**
 * 单轮运行的可观测指标。
 * token 为 TokenEstimator 的估算值（当前模型响应未暴露 usage），用于趋势观察，不等同于计费。
 */
public record AgentMetrics(long durationMs, int steps, int toolCalls, int memoryHits,
                           int estimatedInputTokens, int estimatedOutputTokens) {

    public String durationText() {
        if (durationMs < 60_000) {
            return String.format(java.util.Locale.ROOT, "%.1fs", durationMs / 1000.0);
        }
        long seconds = durationMs / 1000;
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    public String traceLine() {
        return "完成 · " + durationText()
                + " · " + steps + " 步"
                + " · 工具 " + toolCalls + " 次"
                + " · 记忆 " + memoryHits + " 条"
                + " · tokens≈" + estimatedInputTokens + "/" + estimatedOutputTokens;
    }
}
