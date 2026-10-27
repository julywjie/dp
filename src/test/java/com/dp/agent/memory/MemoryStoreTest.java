package com.dp.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @TempDir
    Path dir;

    @Test
    void savesAndLoadsEntries() throws Exception {
        MemoryStore store = new MemoryStore(dir);
        MemoryData data = new MemoryData();
        data.setEntries(new ArrayList<>(List.of(
                MemoryEntry.manual(MemoryKind.PROJECT, "项目使用 JavaFX", false, false, 4))));
        store.save(data);

        MemoryData loaded = new MemoryStore(dir).load();
        assertEquals(1, loaded.getEntries().size());
        assertEquals("项目使用 JavaFX", loaded.getEntries().get(0).text());
        assertTrue(loaded.isEnabled());
    }

    @Test
    void corruptFileIsQuarantined() throws Exception {
        MemoryStore store = new MemoryStore(dir);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "{ broken");

        MemoryData loaded = store.load();
        assertTrue(loaded.getEntries().isEmpty());
        try (Stream<Path> files = Files.list(store.file().getParent())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("memories.corrupt-")));
        }
    }
}
