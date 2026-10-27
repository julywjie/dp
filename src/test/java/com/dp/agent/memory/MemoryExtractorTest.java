package com.dp.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractorTest {

    @Test
    void parsesJsonInsideCodeFence() {
        String raw = """
                好的，提取结果如下：
                ```json
                [{"kind":"PREFERENCE","text":"用户偏好简洁回答","importance":4},
                 {"kind":"PROJECT","text":"项目使用 Java 17","importance":3}]
                ```
                """;

        List<MemoryEntry> entries = MemoryExtractor.parseResponse(raw, "session-1");
        assertEquals(2, entries.size());
        assertEquals(MemoryKind.PREFERENCE, entries.get(0).kind());
        assertEquals("用户偏好简洁回答", entries.get(0).text());
        assertEquals("auto", entries.get(0).source());
        assertEquals("session-1", entries.get(0).sessionId());
    }

    @Test
    void filtersSecretsAndInvalidItems() {
        String raw = """
                [{"kind":"FACT","text":"API Key: sk-abcdefghijklmnop","importance":5},
                 {"kind":"FACT","text":"","importance":3},
                 {"kind":"GENERAL","text":"用户在做桌面项目","importance":3}]
                """;

        List<MemoryEntry> entries = MemoryExtractor.parseResponse(raw, "s");
        assertEquals(1, entries.size());
        assertEquals("用户在做桌面项目", entries.get(0).text());
        assertEquals(MemoryKind.FACT, entries.get(0).kind());
    }

    @Test
    void invalidOrEmptyResponseIsSafe() {
        assertTrue(MemoryExtractor.parseResponse(null, "s").isEmpty());
        assertTrue(MemoryExtractor.parseResponse("没有可提取内容", "s").isEmpty());
        assertTrue(MemoryExtractor.parseResponse("{bad json", "s").isEmpty());
    }
}
