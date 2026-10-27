package com.dp.agent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {

    @TempDir
    Path dir;

    private MemoryService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void manualMemoryRoundTripAndDedupe() {
        service = new MemoryService(dir);
        service.addManual(MemoryKind.PROJECT, "项目使用 JavaFX", false, false, 3);
        service.addManual(MemoryKind.PROJECT, "项目使用 JavaFX", false, false, 4);

        List<MemoryEntry> entries = service.entries(false);
        assertEquals(1, entries.size());
        assertEquals(4, entries.get(0).importance());

        MemoryService reopened = new MemoryService(dir);
        assertEquals(1, reopened.entries(false).size());
        reopened.close();
    }

    @Test
    void generalMemoryIsPersistedAndAlwaysRetrieved() {
        service = new MemoryService(dir);
        service.addManual(MemoryKind.GENERAL, "回答尽量简洁", true, true, 5);

        MemoryService.Retrieved retrieved = service.retrieve("今天天气怎么样");
        assertNotNull(retrieved);
        assertTrue(retrieved.text().contains("回答尽量简洁"));
        assertEquals(1, retrieved.entries().size());
    }

    @Test
    void duplicateMergeKeepsMoreCompleteText() {
        service = new MemoryService(dir);
        service.addManual(MemoryKind.PROJECT, "项目使用 JavaFX 开发桌面应用",
                false, false, 3);
        service.addManual(MemoryKind.PROJECT, "项目使用 JavaFX", false, false, 3);

        assertEquals("项目使用 JavaFX 开发桌面应用",
                service.entries(false).get(0).text());

        service.addManual(MemoryKind.PROJECT,
                "项目使用 JavaFX 开发桌面应用，并用 LangGraph4j 编排",
                false, false, 3);
        assertTrue(service.entries(false).get(0).text().contains("LangGraph4j"));
    }

    @Test
    void updateAndDeleteWork() {
        service = new MemoryService(dir);
        MemoryEntry entry = service.addManual(
                MemoryKind.FACT, "旧的记忆内容", false, false, 3);
        assertNotNull(entry);

        service.update(entry.id(), MemoryKind.PREFERENCE, "新的记忆内容", true, 5);
        MemoryEntry updated = service.entries(false).get(0);
        assertEquals("新的记忆内容", updated.text());
        assertEquals(MemoryKind.PREFERENCE, updated.kind());
        assertTrue(updated.pinned());

        service.delete(entry.id());
        assertTrue(service.entries(false).isEmpty());
    }

    @Test
    void togglesArePersisted() {
        service = new MemoryService(dir);
        service.setEnabled(false);
        service.setAutoExtract(false);
        service.close();

        MemoryService reopened = new MemoryService(dir);
        assertFalse(reopened.isEnabled());
        assertFalse(reopened.isAutoExtract());
        reopened.close();
        service = null;
    }
}
