package com.dp.agent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 工具调用审计：每行一个 JSON 事件，追加写入本地文件。
 * 写入失败不阻断工具执行，只记录一次错误供 UI 提示。
 */
public final class ToolAuditLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Event(String ts, String sessionId, String tool, String args,
                        boolean safe, String decision, long durationMs,
                        boolean success, String error) {
    }

    private final Path file;
    private volatile String lastError;
    private volatile boolean warningEmitted;

    public ToolAuditLog(Path dataDir) {
        this.file = dataDir.resolve("audit").resolve("tool-calls.jsonl");
    }

    public synchronized boolean append(Event event) {
        try {
            Files.createDirectories(file.getParent());
            String line = MAPPER.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    /** 只在首次失败时返回 true，避免每次调用都刷屏。 */
    public boolean warnOnce() {
        if (lastError == null || warningEmitted) {
            return false;
        }
        warningEmitted = true;
        return true;
    }

    public String lastError() {
        return lastError;
    }

    public Path file() {
        return file;
    }
}
