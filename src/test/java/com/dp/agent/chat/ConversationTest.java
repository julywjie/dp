package com.dp.agent.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTest {

    @Test
    void failureRollbackRemovesUserFromHistoryButKeepsDisplay() {
        Conversation conversation = new Conversation();
        conversation.addUser("会失败的问题");
        conversation.addError("请求超时");
        conversation.rollbackLastUser();
        conversation.rollbackLastUser(); // 幂等

        assertTrue(conversation.historySnapshot().isEmpty());
        assertEquals(2, conversation.displaySnapshot().size());
    }

    @Test
    void clearResetsCompactionState() {
        Conversation conversation = new Conversation();
        conversation.addUser("u");
        conversation.addAssistant("a");
        conversation.setCompaction("摘要", 2);
        conversation.clear();

        assertEquals("", conversation.summary());
        assertEquals(0, conversation.summarizedCount());
        assertTrue(conversation.isEmpty());
    }

    @Test
    void compactionCountIsClampedToHistory() {
        Conversation conversation = new Conversation();
        conversation.addUser("u");
        conversation.setCompaction("摘要", 99);
        assertEquals(1, conversation.summarizedCount());
    }

    @Test
    void prepareRetryKeepsUserAndRemovesLastAnswerAndTraces() {
        Conversation conversation = new Conversation();
        conversation.addUser("第一轮");
        conversation.addAssistant("回答一");
        conversation.addUser("第二轮");
        conversation.addTrace("工具调用");
        conversation.addAssistant("回答二");

        String prompt = conversation.prepareRetry();

        assertEquals("第二轮", prompt);
        assertEquals(3, conversation.historySnapshot().size());
        var display = conversation.displaySnapshot();
        assertEquals(3, display.size());
        assertEquals(UiMessage.Kind.USER, display.get(2).kind());
        assertEquals("第二轮", display.get(2).text());
        assertEquals(null, conversation.prepareRetry());
    }

    @Test
    void tracesAreGroupedPerTurn() {
        Conversation conversation = new Conversation();
        conversation.addUser("第一轮");
        conversation.addTrace("第一步");
        conversation.addTrace("第二步");
        conversation.addAssistant("回答");
        conversation.addUser("第二轮");
        conversation.addTrace("新一轮轨迹");

        var display = conversation.displaySnapshot();
        assertEquals(5, display.size());
        assertEquals(UiMessage.Kind.TRACE_GROUP, display.get(1).kind());
        assertEquals("第一步\n第二步", display.get(1).text());
        assertEquals(UiMessage.Kind.TRACE_GROUP, display.get(4).kind());
        assertEquals("新一轮轨迹", display.get(4).text());
    }
}
