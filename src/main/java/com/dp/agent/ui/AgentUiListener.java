package com.dp.agent.ui;

import com.dp.agent.core.AgentService;
import javafx.application.Platform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 工作线程 → JavaFX 线程的事件桥。
 * 负责 token 合帧与“过期会话/过期轮次”过滤；业务动作由 Host 提供。
 */
final class AgentUiListener implements AgentService.Listener {

    interface Host {
        boolean active();

        void appendToken(String chunk);

        void appendTrace(String event);

        void compaction(String summary, int covered);

        void complete(String answer);

        void error(String message);
    }

    private final Host host;
    /** token 合并缓冲：把成百上千次 runLater 压成每帧最多一次 UI 更新。 */
    private final Object bufferLock = new Object();
    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    AgentUiListener(Host host) {
        this.host = host;
    }

    private void fx(Runnable action) {
        Platform.runLater(() -> {
            if (host.active()) {
                action.run();
            }
        });
    }

    /** 收尾事件先排空缓冲，保证最后几个 token 不被丢弃。 */
    private void fxAfterFlush(Runnable action) {
        Platform.runLater(() -> {
            flush();
            if (host.active()) {
                action.run();
            }
        });
    }

    private void flush() {
        flushScheduled.set(false);
        String chunk;
        synchronized (bufferLock) {
            if (buffer.length() == 0) {
                return;
            }
            chunk = buffer.toString();
            buffer.setLength(0);
        }
        if (host.active()) {
            host.appendToken(chunk);
        }
    }

    @Override
    public void onToken(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        synchronized (bufferLock) {
            buffer.append(chunk);
        }
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flush);
        }
    }

    @Override
    public void onTrace(String event) {
        fx(() -> host.appendTrace(event));
    }

    @Override
    public void onCompaction(String summary, int coveredCount) {
        fx(() -> host.compaction(summary, coveredCount));
    }

    @Override
    public void onComplete(String answer) {
        fxAfterFlush(() -> host.complete(answer));
    }

    @Override
    public void onError(String message) {
        fxAfterFlush(() -> host.error(message));
    }
}
