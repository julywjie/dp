package com.dp.agent.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一轮会话：同时维护两份数据——
 *   history：发给模型的完整多轮消息（仅用户输入与最终回答，不含中间工具消息，
 *            保证对任意提供商兼容且上下文干净）；
 *   display：UI 展示用消息（含轨迹/错误）。
 * summary/summarizedCount 记录「较早对话已被压缩」的状态，供上下文预算使用。
 */
public class Conversation {

    private final List<ChatMessage> history = new ArrayList<>();
    private final List<UiMessage> display = new ArrayList<>();
    private String summary = "";
    private int summarizedCount;

    public List<ChatMessage> historySnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public List<UiMessage> getDisplay() {
        return display;
    }

    public List<UiMessage> displaySnapshot() {
        return List.copyOf(display);
    }

    public String summary() {
        return summary;
    }

    public int summarizedCount() {
        return summarizedCount;
    }

    public void addUser(String text) {
        history.add(new UserMessage(text));
        display.add(UiMessage.user(text));
    }

    public void addAssistant(String answer) {
        history.add(new AiMessage(answer));
        display.add(UiMessage.assistant(answer));
    }

    /** 同一轮的轨迹合并为一条 TRACE_GROUP，避免聊天气泡被工具日志刷屏。 */
    public void addTrace(String event) {
        if (!display.isEmpty()
                && display.get(display.size() - 1).kind() == UiMessage.Kind.TRACE_GROUP) {
            UiMessage last = display.get(display.size() - 1);
            display.set(display.size() - 1,
                    UiMessage.traceGroup(last.text() + "\n" + event));
        } else {
            display.add(UiMessage.traceGroup(event));
        }
    }

    public void addError(String message) {
        display.add(UiMessage.error(message));
    }

    /** 最后一轮失败时回滚用户输入，避免污染 history。 */
    public void rollbackLastUser() {
        if (!history.isEmpty() && history.get(history.size() - 1) instanceof UserMessage) {
            history.remove(history.size() - 1);
        }
    }

    /**
     * 准备重新生成最后一轮：移除最后一条助手回答及其后的展示轨迹，
     * 保留历史中的用户问题；调用方以 appendUser=false 运行 Agent，
     * 避免重复追加当前问题。不满足条件时返回 null。
     */
    public String prepareRetry() {
        if (history.isEmpty() || !(history.get(history.size() - 1) instanceof AiMessage)) {
            return null;
        }
        history.remove(history.size() - 1);
        if (history.isEmpty() || !(history.get(history.size() - 1) instanceof UserMessage user)) {
            return null;
        }
        String prompt = user.singleText();
        int lastUserDisplay = -1;
        for (int i = display.size() - 1; i >= 0; i--) {
            if (display.get(i).kind() == UiMessage.Kind.USER) {
                lastUserDisplay = i;
                break;
            }
        }
        if (lastUserDisplay >= 0) {
            display.subList(lastUserDisplay + 1, display.size())
                    .removeIf(message -> message.kind() != UiMessage.Kind.USER);
        }
        summarizedCount = Math.min(summarizedCount, history.size());
        return prompt;
    }

    public boolean isEmpty() {
        return history.isEmpty() && display.isEmpty();
    }

    /** 记录压缩进度；covered 为已被摘要覆盖的历史消息数。 */
    public void setCompaction(String summary, int covered) {
        this.summary = summary == null ? "" : summary;
        this.summarizedCount = Math.max(0, Math.min(covered, history.size()));
    }

    public void restore(List<ChatMessage> restoredHistory, List<UiMessage> restoredDisplay,
                        String restoredSummary, int covered) {
        history.clear();
        display.clear();
        history.addAll(restoredHistory);
        display.addAll(restoredDisplay);
        setCompaction(restoredSummary, covered);
    }

    public void clear() {
        history.clear();
        display.clear();
        summary = "";
        summarizedCount = 0;
    }
}
