package com.dp.agent.memory;

import java.util.UUID;

/**
 * 一条记忆。
 *
 * general=true 表示「通用记忆」：用户手动维护、每次对话都注入；
 * general=false 表示「长期记忆」：按与当前问题的相关度召回。
 * source 为 auto（模型提取）或 manual（用户添加）。
 */
public record MemoryEntry(String id, MemoryKind kind, String text, boolean general,
                          boolean pinned, int importance, String source, String sessionId,
                          long createdAt, long updatedAt) {

    public MemoryEntry {
        if (kind == null) {
            kind = MemoryKind.FACT;
        }
        if (text == null) {
            text = "";
        }
        importance = Math.max(1, Math.min(5, importance));
        if (source == null || source.isBlank()) {
            source = "manual";
        }
        if (sessionId == null) {
            sessionId = "";
        }
        long now = System.currentTimeMillis();
        if (createdAt <= 0) {
            createdAt = now;
        }
        if (updatedAt <= 0) {
            updatedAt = now;
        }
    }

    public static MemoryEntry manual(MemoryKind kind, String text, boolean general,
                                     boolean pinned, int importance) {
        long now = System.currentTimeMillis();
        return new MemoryEntry(UUID.randomUUID().toString(), kind, text.trim(), general,
                pinned || general, importance, "manual", "", now, now);
    }

    public static MemoryEntry auto(MemoryKind kind, String text, int importance, String sessionId) {
        long now = System.currentTimeMillis();
        return new MemoryEntry(UUID.randomUUID().toString(), kind, text.trim(), false,
                false, importance, "auto", sessionId, now, now);
    }

    public MemoryEntry updated(String newText, MemoryKind newKind, boolean newPinned,
                               int newImportance) {
        return new MemoryEntry(id, newKind, newText == null ? text : newText.trim(),
                general, newPinned || general, newImportance, source, sessionId,
                createdAt, System.currentTimeMillis());
    }

    public MemoryEntry refreshed(String newText) {
        return new MemoryEntry(id, kind, newText, general, pinned, importance,
                source, sessionId,
                createdAt, System.currentTimeMillis());
    }
}
