package com.dp.agent.core.context;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.config.ProviderType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {

    @Test
    void summarizesOlderMessagesAndAdvancesCovered() throws Exception {
        List<String> traces = new ArrayList<>();
        ContextCompactor compactor = new ContextCompactor((cfg, key, prompt) -> "压缩后的摘要");
        ContextCompactor.Result result = compactor.compact(
                provider(), "key", history(20), 0, "", "你是助手。", "继续", 4, traces::add);

        assertTrue(result.summarized());
        assertEquals("压缩后的摘要", result.summary());
        assertTrue(result.covered() > 0);
        assertTrue(traces.stream().anyMatch(t -> t.contains("已摘要")));
    }

    @Test
    void summaryFailureFallsBackToTruncation() throws Exception {
        List<String> traces = new ArrayList<>();
        ContextCompactor compactor = new ContextCompactor((cfg, key, prompt) -> {
            throw new IllegalStateException("network down");
        });
        ContextCompactor.Result result = compactor.compact(
                provider(), "key", history(20), 0, "旧摘要", "你是助手。", "继续", 4, traces::add);

        assertTrue(!result.summarized());
        assertTrue(result.covered() > 0);
        assertTrue(result.summary().contains("旧摘要"));
        assertTrue(result.summary().contains("已省略"));
        assertTrue(traces.stream().anyMatch(t -> t.contains("摘要失败")));
    }

    @Test
    void interruptIsPropagated() {
        ContextCompactor compactor = new ContextCompactor((cfg, key, prompt) -> {
            throw new InterruptedException("stopped");
        });
        assertThrows(InterruptedException.class, () -> compactor.compact(
                provider(), "key", history(20), 0, "", "你是助手。", "继续", 4, t -> {
                }));
    }

    @Test
    void tooLongInputIsRejected() {
        ContextCompactor compactor = new ContextCompactor((cfg, key, prompt) -> "x");
        assertThrows(ContextCompactor.InputTooLongException.class, () -> compactor.compact(
                provider(), "key", history(1), 0, "", "你是助手。", "你".repeat(5000), 4, t -> {
                }));
    }

    private static ProviderConfig provider() {
        ProviderConfig cfg = ProviderConfig.of("test", "Test", ProviderType.OPENAI_COMPATIBLE,
                "http://localhost", "test-model");
        cfg.setContextWindow(4000);
        cfg.setMaxTokens(512);
        return cfg;
    }

    private static List<ChatMessage> history(int pairs) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            messages.add(UserMessage.from("u" + i + " " + "x".repeat(400)));
            messages.add(AiMessage.from("a" + i + " " + "y".repeat(400)));
        }
        return messages;
    }
}
