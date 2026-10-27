package com.dp.agent.session;

import com.dp.agent.chat.Conversation;
import com.dp.agent.chat.UiMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个会话的持久化快照。
 * history 只含 user/assistant 文本（与 Conversation 的上下文规则一致），
 * display 保留完整 UI 轨迹（含 TRACE / ERROR）。
 */
public class SessionData {

    public static final int CURRENT_VERSION = 1;
    public static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_LIMIT = 36;

    private int version = CURRENT_VERSION;
    private String id = "";
    private String title = DEFAULT_TITLE;
    private long createdAt;
    private long updatedAt;
    private String summary = "";
    private int summarizedCount;
    private List<SessionMessage> history = new ArrayList<>();
    private List<UiMessage> display = new ArrayList<>();

    public static SessionData empty(String id) {
        long now = System.currentTimeMillis();
        SessionData data = new SessionData();
        data.id = id;
        data.createdAt = now;
        data.updatedAt = now;
        return data;
    }

    public static SessionData capture(SessionMeta meta, Conversation conversation) {
        SessionData data = new SessionData();
        data.id = meta.id();
        data.createdAt = meta.createdAt();
        data.updatedAt = System.currentTimeMillis();
        data.title = meta.title();
        data.summary = conversation.summary();
        data.summarizedCount = conversation.summarizedCount();
        for (ChatMessage message : conversation.historySnapshot()) {
            if (message instanceof UserMessage user) {
                data.history.add(new SessionMessage(SessionMessage.USER, user.singleText()));
            } else if (message instanceof AiMessage ai && ai.text() != null) {
                data.history.add(new SessionMessage(SessionMessage.ASSISTANT, ai.text()));
            }
        }
        data.display = new ArrayList<>(conversation.displaySnapshot());
        if (data.title == null || data.title.isBlank() || DEFAULT_TITLE.equals(data.title)) {
            data.title = deriveTitle(data.display);
        }
        return data;
    }

    public void restoreInto(Conversation conversation) {
        sanitize();
        List<ChatMessage> restoredHistory = new ArrayList<>();
        for (SessionMessage message : history) {
            if (SessionMessage.USER.equalsIgnoreCase(message.role())) {
                restoredHistory.add(new UserMessage(message.text()));
            } else if (SessionMessage.ASSISTANT.equalsIgnoreCase(message.role())) {
                restoredHistory.add(new AiMessage(message.text()));
            }
        }
        List<UiMessage> restoredDisplay = new ArrayList<>(display);
        if (restoredDisplay.isEmpty() && !restoredHistory.isEmpty()) {
            restoredDisplay = synthesizeDisplay(restoredHistory);
        }
        int covered = Math.max(0, Math.min(summarizedCount, restoredHistory.size()));
        while (covered > 0 && covered < restoredHistory.size()
                && !(restoredHistory.get(covered) instanceof UserMessage)) {
            covered--;
        }
        conversation.restore(restoredHistory, restoredDisplay, summary == null ? "" : summary, covered);
    }

    private static List<UiMessage> synthesizeDisplay(List<ChatMessage> history) {
        List<UiMessage> messages = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message instanceof UserMessage user) {
                messages.add(UiMessage.user(user.singleText()));
            } else if (message instanceof AiMessage ai && ai.text() != null) {
                messages.add(UiMessage.assistant(ai.text()));
            }
        }
        return messages;
    }

    /** 丢弃损坏条目，避免一条坏数据让整个会话不可用（不修改调用方可能不可变的列表）。 */
    public void sanitize() {
        List<SessionMessage> cleanHistory = new ArrayList<>();
        if (history != null) {
            for (SessionMessage message : history) {
                if (message != null && message.role() != null && message.text() != null) {
                    cleanHistory.add(message);
                }
            }
        }
        history = cleanHistory;

        List<UiMessage> cleanDisplay = new ArrayList<>();
        if (display != null) {
            for (UiMessage message : display) {
                if (message != null && message.kind() != null && message.text() != null) {
                    cleanDisplay.add(message);
                }
            }
        }
        display = cleanDisplay;
    }

    public boolean hasContent() {
        for (UiMessage message : display) {
            if (message != null && message.kind() == UiMessage.Kind.USER) {
                return true;
            }
        }
        return false;
    }

    public static String deriveTitle(List<UiMessage> display) {
        for (UiMessage message : display) {
            if (message != null && message.kind() == UiMessage.Kind.USER) {
                String text = message.text().trim().replaceAll("\\s+", " ");
                if (text.isEmpty()) {
                    continue;
                }
                if (text.codePointCount(0, text.length()) <= TITLE_LIMIT) {
                    return text;
                }
                int end = text.offsetByCodePoints(0, TITLE_LIMIT);
                return text.substring(0, end) + "…";
            }
        }
        return DEFAULT_TITLE;
    }

    public SessionMeta toMeta() {
        return new SessionMeta(id, title, createdAt, updatedAt, display == null ? 0 : display.size());
    }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public int getSummarizedCount() { return summarizedCount; }
    public void setSummarizedCount(int summarizedCount) { this.summarizedCount = summarizedCount; }

    public List<SessionMessage> getHistory() { return history; }
    public void setHistory(List<SessionMessage> history) { this.history = history; }

    public List<UiMessage> getDisplay() { return display; }
    public void setDisplay(List<UiMessage> display) { this.display = display; }
}
