package com.dp.agent.chat;

import com.dp.agent.session.SessionData;
import com.dp.agent.session.SessionMeta;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前会话聚合：唯一持有 Conversation，并用 RunToken 标记“哪一轮仍属于当前会话”。
 *
 * 会话切换 / 新建 / 删除时 invalidateRun()，旧一轮的迟到回调会因为 token 不再匹配而丢弃；
 * 不再需要在 ChatController 里手工维护 generation 计数器。
 */
public final class ChatSession {

    public record RunToken(long sequence, String sessionId) {
    }

    private final Conversation conversation = new Conversation();
    private final AtomicLong runSequence = new AtomicLong();
    private final AtomicReference<RunToken> currentRun = new AtomicReference<>();

    public List<ChatMessage> historySnapshot() {
        return conversation.historySnapshot();
    }

    public String summary() {
        return conversation.summary();
    }

    public int summarizedCount() {
        return conversation.summarizedCount();
    }

    public void addUser(String text) {
        conversation.addUser(text);
    }

    public void addAssistant(String answer) {
        conversation.addAssistant(answer);
    }

    public void addTrace(String event) {
        conversation.addTrace(event);
    }

    public void addError(String message) {
        conversation.addError(message);
    }

    public void rollbackLastUser() {
        conversation.rollbackLastUser();
    }

    public String prepareRetry() {
        return conversation.prepareRetry();
    }

    public List<UiMessage> displaySnapshot() {
        return conversation.displaySnapshot();
    }

    public boolean isEmpty() {
        return conversation.isEmpty();
    }

    public void setCompaction(String summary, int covered) {
        conversation.setCompaction(summary, covered);
    }

    public void clear() {
        conversation.clear();
    }

    public void restore(SessionData data) {
        data.restoreInto(conversation);
    }

    public SessionData capture(SessionMeta meta) {
        return SessionData.capture(meta, conversation);
    }

    /** 开始新一轮；同一时刻只保留一个有效 token。 */
    public RunToken beginRun(String sessionId) {
        RunToken token = new RunToken(runSequence.incrementAndGet(), sessionId);
        currentRun.set(token);
        return token;
    }

    public boolean isActive(RunToken token) {
        return token != null && currentRun.get() == token;
    }

    public boolean hasActiveRun() {
        return currentRun.get() != null;
    }

    public void finishRun(RunToken token) {
        currentRun.compareAndSet(token, null);
    }

    /** 会话切换 / 新建 / 删除时调用，让旧一轮的所有迟到回调失效。 */
    public void invalidateRun() {
        currentRun.set(null);
    }
}
