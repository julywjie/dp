package com.dp.agent.ui;

import com.dp.agent.chat.Conversation;
import com.dp.agent.core.AgentService;
import com.dp.agent.session.SessionData;
import com.dp.agent.session.SessionManager;
import com.dp.agent.session.SessionMeta;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 会话生命周期与异步 IO：恢复、切换、新建、删除、保存。
 * 所有会话文件读写都在单线程 session-io 上按提交顺序执行。
 */
final class SessionCoordinator implements AutoCloseable {

    private final SessionManager sessions;
    private final Conversation conversation;
    private final AgentService agent;
    private final Supplier<ChatView> viewSupplier;
    private final Consumer<String> discardInFlight;
    private final Runnable newGeneration;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "session-io");
        thread.setDaemon(true);
        return thread;
    });
    private long loadGeneration;
    private volatile boolean shuttingDown;

    SessionCoordinator(SessionManager sessions, Conversation conversation, AgentService agent,
                       Supplier<ChatView> viewSupplier, Consumer<String> discardInFlight,
                       Runnable newGeneration) {
        this.sessions = sessions;
        this.conversation = conversation;
        this.agent = agent;
        this.viewSupplier = viewSupplier;
        this.discardInFlight = discardInFlight;
        this.newGeneration = newGeneration;
    }

    String currentId() {
        return sessions.currentId();
    }

    void restoreInitial() {
        long token = ++loadGeneration;
        io.submit(() -> {
            List<SessionMeta> metas = sessions.refreshIndex();
            var first = metas.stream()
                    .map(meta -> sessions.load(meta.id()))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .findFirst();
            Platform.runLater(() -> {
                if (token != loadGeneration) {
                    if (!shuttingDown) {
                        refreshSidebar();
                    }
                    return;
                }
                if (first.isPresent()) {
                    sessions.adopt(first.get());
                    first.get().restoreInto(conversation);
                    view().setMessages(conversation.displaySnapshot());
                    view().setBusy(false, "就绪");
                } else {
                    startEmptySession();
                    view().setBusy(false, "就绪");
                }
                refreshSidebar();
            });
        });
    }

    void open(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (id.equals(sessions.currentId())) {
            view().showChat();
            return;
        }
        discardInFlight.accept("已中断（切换会话）");
        saveCurrent();
        newGeneration.run();
        long token = ++loadGeneration;
        view().setBusy(true, "正在加载会话…");

        io.submit(() -> {
            var data = sessions.load(id);
            Platform.runLater(() -> {
                if (token != loadGeneration) {
                    return;
                }
                if (data.isEmpty()) {
                    startEmptySession();
                    view().setBusy(false, "会话读取失败，已新建空会话");
                    refreshSidebar();
                    return;
                }
                sessions.adopt(data.get());
                data.get().restoreInto(conversation);
                view().setMessages(conversation.displaySnapshot());
                view().showChat();
                if (agent.isBusy()) {
                    view().setBusy(true, "正在停止旧任务 …");
                    watchIdle(token);
                } else {
                    view().setBusy(false, "就绪");
                }
                refreshSidebar();
            });
        });
    }

    void startNew() {
        discardInFlight.accept("已中断（新建会话）");
        saveCurrent();
        newGeneration.run();
        long token = ++loadGeneration;
        sessions.startEmpty();
        conversation.clear();
        view().clearChat();
        view().showChat();
        if (agent.isBusy()) {
            view().setBusy(true, "正在停止旧任务 …");
            watchIdle(token);
        } else {
            view().setBusy(false, "已开新会话");
        }
        refreshSidebar();
    }

    void delete(String id) {
        loadGeneration++;
        SessionMeta target = sessions.list().stream()
                .filter(meta -> meta.id().equals(id))
                .findFirst()
                .orElse(null);
        if (target == null || !confirmDelete(target)) {
            return;
        }
        if (!id.equals(sessions.currentId())) {
            if (!sessions.delete(id)) {
                view().showToast("删除失败：文件可能被占用。", true);
            }
            refreshSidebar();
            return;
        }

        discardInFlight.accept("已中断（会话已删除）");
        newGeneration.run();
        if (!sessions.delete(id)) {
            view().setBusy(false, "删除失败，会话已保留");
            view().showToast("删除失败：文件可能被占用。", true);
            refreshSidebar();
            return;
        }
        conversation.clear();
        view().clearChat();

        List<SessionMeta> remaining = sessions.list();
        if (!remaining.isEmpty()) {
            open(remaining.get(0).id());
        } else {
            sessions.startEmpty();
            view().showChat();
            if (agent.isBusy()) {
                view().setBusy(true, "正在停止旧任务 …");
                watchIdle(loadGeneration);
            } else {
                view().setBusy(false, "就绪");
            }
        }
        refreshSidebar();
    }

    void saveCurrent() {
        var meta = sessions.current();
        if (meta.isEmpty() || conversation.isEmpty()) {
            return;
        }
        SessionData data = SessionData.capture(meta.get(), conversation);
        try {
            io.submit(() -> {
                try {
                    sessions.writeToDisk(data);
                    Platform.runLater(() -> {
                        sessions.acknowledgeSaved(data);
                        if (!shuttingDown) {
                            refreshSidebar();
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        ChatView view = viewSupplier.get();
                        if (view != null) {
                            view.showToast("会话保存失败: " + e.getMessage(), true);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            try {
                sessions.writeToDisk(data);
                sessions.acknowledgeSaved(data);
            } catch (Exception ignored) {
                // 关闭阶段的错误不再打扰用户
            }
        }
    }

    void refreshSidebar() {
        ChatView view = viewSupplier.get();
        if (view != null) {
            view.sessionSidebar().setSessions(sessions.list(), sessions.currentId());
        }
    }

    private void startEmptySession() {
        sessions.startEmpty();
        conversation.clear();
        view().clearChat();
    }

    private void watchIdle(long token) {
        agent.whenIdle(() -> Platform.runLater(() -> {
            if (!shuttingDown && token == loadGeneration) {
                view().setBusy(false, "就绪");
            }
        }));
    }

    private boolean confirmDelete(SessionMeta meta) {
        return UiDialogs.confirm(ownerWindow(), "删除会话",
                "删除「" + meta.title() + "」？",
                "会话内容将从本机移除，且不可恢复。");
    }

    private javafx.stage.Window ownerWindow() {
        ChatView view = viewSupplier.get();
        if (view == null || view.providerBox().getScene() == null) {
            return null;
        }
        return view.providerBox().getScene().getWindow();
    }

    private ChatView view() {
        ChatView view = viewSupplier.get();
        if (view == null) {
            throw new IllegalStateException("SessionCoordinator 在视图挂载前被调用");
        }
        return view;
    }

    @Override
    public void close() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        loadGeneration++;
        saveCurrent();
        io.shutdown();
        try {
            if (!io.awaitTermination(2, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        }
    }
}
