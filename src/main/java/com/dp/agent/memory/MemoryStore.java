package com.dp.agent.memory;

import com.dp.agent.storage.JsonFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆库文件：%APPDATA%\dp-agent\memory\memories.json。
 * 写入复用 JsonFiles 的原子替换；损坏文件隔离保留，不静默覆盖。
 */
public final class MemoryStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MemoryStore(Path dataDir) {
        this.file = dataDir.resolve("memory").resolve("memories.json");
    }

    public MemoryData load() {
        if (!Files.exists(file)) {
            return new MemoryData();
        }
        try {
            MemoryData data = mapper.readValue(file.toFile(), MemoryData.class);
            if (data == null) {
                return new MemoryData();
            }
            if (data.getEntries() == null) {
                data.setEntries(new java.util.ArrayList<>());
            }
            return data;
        } catch (Exception e) {
            quarantine(e);
            return new MemoryData();
        }
    }

    public void save(MemoryData data) throws IOException {
        JsonFiles.writeAtomically(file, mapper, data);
    }

    private void quarantine(Exception cause) {
        Path target = file.resolveSibling("memories.corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, target);
        } catch (IOException ignored) {
            // 备份失败也不阻塞启动
        }
        System.err.println("[dp-agent] 记忆库损坏，已隔离: " + cause.getMessage());
    }

    public Path file() {
        return file;
    }
}
