package com.dp.agent.session;

import com.dp.agent.chat.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsRoundTrip() throws Exception {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        conversation.addUser("你好");
        conversation.addAssistant("你好，有什么可以帮你？");
        conversation.addTrace("🔧 决定调用工具");
        conversation.setCompaction("较早对话摘要", 0);

        SessionData data = SessionData.capture(
                new SessionMeta("s1", "你好", 1000L, 1000L, 3), conversation);
        store.save(data);

        SessionData loaded = store.load("s1").orElseThrow();
        assertEquals("s1", loaded.getId());
        assertEquals(2, loaded.getHistory().size());
        assertEquals(3, loaded.getDisplay().size());
        assertEquals("较早对话摘要", loaded.getSummary());

        Conversation restored = new Conversation();
        loaded.restoreInto(restored);
        assertEquals(2, restored.historySnapshot().size());
        assertEquals(3, restored.displaySnapshot().size());
        assertEquals("较早对话摘要", restored.summary());
    }

    @Test
    void derivesTitleFromFirstUserMessageAndTruncates() {
        Conversation conversation = new Conversation();
        conversation.addUser("把下面这段 JSON 格式化，要求保留键顺序并且缩进两个空格，同时不要改变数值精度，"
                + "最后再给我一份字段说明。");
        SessionData data = SessionData.capture(
                new SessionMeta("s2", SessionData.DEFAULT_TITLE, 1L, 1L, 0), conversation);
        assertTrue(data.getTitle().endsWith("…"));
        assertTrue(data.getTitle().codePointCount(0, data.getTitle().length()) <= 37);
    }

    @Test
    void listsNewestFirstAndSkipsCorruptFiles() throws Exception {
        SessionStore store = new SessionStore(tempDir);
        SessionData older = SessionData.empty("older");
        older.setDisplay(List.of(com.dp.agent.chat.UiMessage.user("旧会话")));
        older.setUpdatedAt(1000L);
        store.save(older);

        SessionData newer = SessionData.empty("newer");
        newer.setDisplay(List.of(com.dp.agent.chat.UiMessage.user("新会话")));
        newer.setUpdatedAt(2000L);
        store.save(newer);

        Files.writeString(tempDir.resolve("sessions").resolve("broken.json"), "{ not json");

        List<SessionMeta> metas = store.list();
        assertEquals(2, metas.size());
        assertEquals("newer", metas.get(0).id());
        assertEquals("older", metas.get(1).id());
    }

    @Test
    void rejectsTraversalIdsAndLeavesNoTempFiles() throws Exception {
        SessionStore store = new SessionStore(tempDir);
        assertFalse(store.delete("../evil"));
        assertTrue(store.load("a/b").isEmpty());
        assertThrows(IOException.class, () -> store.save(SessionData.empty("../evil")));

        SessionData data = SessionData.empty("ok-id");
        data.setDisplay(List.of(com.dp.agent.chat.UiMessage.user("hi")));
        store.save(data);
        try (Stream<Path> files = Files.list(tempDir.resolve("sessions"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void sanitizesBrokenSummaryBoundaryOnRestore() {
        Conversation conversation = new Conversation();
        SessionData data = SessionData.empty("x");
        data.setHistory(List.of(
                new SessionMessage(SessionMessage.USER, "u1"),
                new SessionMessage(SessionMessage.ASSISTANT, "a1"),
                new SessionMessage(SessionMessage.USER, "u2"),
                new SessionMessage(SessionMessage.ASSISTANT, "a2")));
        data.setSummarizedCount(1); // 落在 assistant 上，应回退到 0
        data.restoreInto(conversation);
        assertEquals(0, conversation.summarizedCount());
    }
}
