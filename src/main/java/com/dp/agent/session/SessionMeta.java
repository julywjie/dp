package com.dp.agent.session;

/** 会话列表项的元数据（不读取完整内容）。 */
public record SessionMeta(String id, String title, long createdAt, long updatedAt, int messageCount) {
}
