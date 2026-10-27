package com.dp.agent.chat;

/**
 * 聊天区的一条展示消息（用户 / 助手 / 执行轨迹 / 错误）。
 */
public record UiMessage(Kind kind, String text) {

    public enum Kind {
        USER, ASSISTANT, TRACE, ERROR, TRACE_GROUP
    }

    public static UiMessage user(String text) {
        return new UiMessage(Kind.USER, text);
    }

    public static UiMessage assistant(String text) {
        return new UiMessage(Kind.ASSISTANT, text);
    }

    public static UiMessage trace(String text) {
        return new UiMessage(Kind.TRACE, text);
    }

    /** 一轮对话的执行轨迹：text 内用换行分隔多条轨迹。 */
    public static UiMessage traceGroup(String text) {
        return new UiMessage(Kind.TRACE_GROUP, text);
    }

    public static UiMessage error(String text) {
        return new UiMessage(Kind.ERROR, text);
    }
}
