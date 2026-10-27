package com.dp.agent.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话生命周期与内存缓存。当前会话可以为空（尚未落盘），
 * 空会话只出现在侧栏，不产生文件。
 */
public final class SessionManager {

    private final SessionStore store;
    private final Map<String, SessionMeta> cache = new LinkedHashMap<>();
    private SessionMeta current;

    public SessionManager(Path dataDir) throws IOException {
        this.store = new SessionStore(dataDir);
    }

    /**
     * 从磁盘刷新会话索引。构造时不再同步扫描会话目录，调用方应在后台线程执行后
     * 把结果切回 FX 线程。
     */
    public synchronized List<SessionMeta> refreshIndex() {
        List<SessionMeta> disk = store.list();
        cache.clear();
        for (SessionMeta meta : disk) {
            cache.put(meta.id(), meta);
        }
        return list();
    }

    public synchronized Optional<SessionMeta> current() {
        return Optional.ofNullable(current);
    }

    public synchronized String currentId() {
        return current == null ? "" : current.id();
    }

    /** 新建内存会话；首次有内容并保存时才会写盘。 */
    public synchronized SessionMeta startEmpty() {
        long now = System.currentTimeMillis();
        current = new SessionMeta(UUID.randomUUID().toString(), SessionData.DEFAULT_TITLE,
                now, now, 0);
        return current;
    }

    public synchronized void adopt(SessionData data) {
        if (data == null || data.getId() == null) {
            return;
        }
        current = data.toMeta();
        cache.put(current.id(), current);
    }

    public Optional<SessionData> load(String id) {
        return store.load(id);
    }

    public synchronized boolean isCurrent(String id) {
        return current != null && current.id().equals(id);
    }

    /** 最新会话优先；未落盘的当前空会话排在首位。 */
    public synchronized List<SessionMeta> list() {
        List<SessionMeta> items = new ArrayList<>(cache.values());
        if (current != null && !cache.containsKey(current.id())) {
            items.add(0, current);
        }
        items.sort(Comparator.comparingLong(SessionMeta::updatedAt).reversed()
                .thenComparing(SessionMeta::id));
        return items;
    }

    public synchronized void save(SessionData data) throws IOException {
        if (data == null || !data.hasContent()) {
            return;
        }
        store.save(data);
        acknowledgeSaved(data);
    }

    /** 只在后台线程落盘，不触碰内存索引；完成后由 FX 线程调用 acknowledgeSaved。 */
    public void writeToDisk(SessionData data) throws IOException {
        if (data == null || !data.hasContent()) {
            return;
        }
        store.save(data);
    }

    /** 写盘成功后在 FX 线程更新索引；如果期间已切换会话，不改变 current。 */
    public synchronized void acknowledgeSaved(SessionData data) {
        if (data == null || data.getId() == null) {
            return;
        }
        SessionMeta meta = data.toMeta();
        cache.put(meta.id(), meta);
        if (current != null && current.id().equals(meta.id())) {
            current = meta;
        }
    }

    /** true 表示删除成功；失败时保留缓存，避免 UI 会话消失但文件仍在。 */
    public synchronized boolean delete(String id) {
        if (!store.delete(id)) {
            return false;
        }
        cache.remove(id);
        if (isCurrent(id)) {
            current = null;
        }
        return true;
    }
}
