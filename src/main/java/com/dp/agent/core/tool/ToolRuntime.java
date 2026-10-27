package com.dp.agent.core.tool;

import com.dp.agent.config.ToolApprovalPolicy;

import java.util.function.Consumer;

/** 单轮运行时的工具执行上下文。 */
public record ToolRuntime(String sessionId, ToolApprovalPolicy policy,
                          ToolApprovalGate gate, ToolAuditLog audit,
                          Consumer<String> trace) {
}
