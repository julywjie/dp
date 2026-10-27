package com.dp.agent.memory;

import java.util.Locale;

/** 记忆类型；GENERAL 表示用户手动维护、对所有会话生效的通用记忆。 */
public enum MemoryKind {
    PREFERENCE("偏好"),
    FACT("事实"),
    PROJECT("项目"),
    DECISION("决定"),
    GENERAL("通用");

    private final String label;

    MemoryKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static MemoryKind parse(String value) {
        if (value == null || value.isBlank()) {
            return FACT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FACT;
        }
    }
}
