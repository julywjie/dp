package com.dp.agent.core.tool;

/** 用户取消/停止了等待中的工具调用，用于快速结束整轮 Agent 执行。 */
public final class ToolCancelledException extends RuntimeException {

    public ToolCancelledException() {
        super("工具调用已取消");
    }
}
