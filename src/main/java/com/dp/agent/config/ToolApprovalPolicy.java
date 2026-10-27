package com.dp.agent.config;

/** 工具调用确认策略：智能模式只拦未知工具，全部确认则每次调用都询问。 */
public enum ToolApprovalPolicy {

    AUTO("智能（安全工具直接执行）"),
    ALWAYS("全部确认");

    private final String label;

    ToolApprovalPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
