package com.dp.agent.session;

/** 持久化用的模型上下文消息：只保留角色与文本，避免绑定提供商的消息类型。 */
public record SessionMessage(String role, String text) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
