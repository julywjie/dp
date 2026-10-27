package com.dp.agent.core;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.core.context.ContextCompactor;
import com.dp.agent.core.context.LlmSummarizer;
import com.dp.agent.memory.MemoryService;
import com.dp.agent.core.tool.ToolApprovalGate;
import com.dp.agent.core.tool.ToolAuditLog;
import com.dp.agent.core.tool.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LangGraph Agent 编排门面（对 UI 只暴露 submit/cancel/isBusy）。
 *
 * 拓扑为 LangGraph 经典 Agent Executor（ReAct 循环）：
 *   START -> agent(调用模型) -> action(执行工具) -> agent -> ... -> END
 * 由 langgraph4j 的 AgentExecutor 构建，本类负责：
 *   按当前模型配置即时组图、后台线程执行、节点事件/流式 token 翻译回传、
 *   步数熔断、可取消。
 *
 * 流式说明：传入 StreamingChatModel 时，agent 节点内部的生成器把每个增量
 * token 包装为 {@link StreamingOutput} 派发到流上，本类转交 onToken；
 * 这些流式项不计入步数熔断（一次回答可达数百 token）。
 */
public class AgentService implements AutoCloseable {

    /** UI 回调接口；所有方法在工作线程调用，UI 侧需自行切线程。 */
    public interface Listener {
        default void onToken(String chunk) {
        }

        void onTrace(String event);

        void onComplete(String answer);

        void onError(String message);

        /** 上下文被压缩：summary 覆盖前 coveredCount 条历史，控制器需要持久化。 */
        default void onCompaction(String summary, int coveredCount) {
        }
    }

    /** 一轮对话的完整输入，避免长参数列表。 */
    public record RunRequest(ProviderConfig provider, String apiKey, boolean toolsEnabled,
                             boolean streamingEnabled, List<ChatMessage> history,
                             String userText, String sessionId, String summary,
                             int summarizedCount, boolean confirmAllTools,
                             ToolApprovalGate approvalGate, boolean appendUser) {
    }

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 死循环熔断：agent/action 各算一步，60 步足够任何正常任务。 */
    private static final int MAX_STEPS = 60;
    /** 停止后等待在途 HTTP 退出的反馈时限；超时只解除 UI 等待，不强杀线程。 */
    private static final int STOP_WATCHDOG_SECONDS = 20;

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-watchdog");
        t.setDaemon(true);
        return t;
    });
    /**
     * 当前可提交的运行。watchdog 放弃一个卡死的运行后会把它置空并释放 busy，
     * 旧线程继续在后台退出，但它的回调由各自的 terminal 门禁丢弃。
     */
    private final AtomicReference<RunContext> active = new AtomicReference<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ContextCompactor compactor = new ContextCompactor(new LlmSummarizer());
    private final ToolAuditLog auditLog;
    private final ToolRegistry toolRegistry;
    private final AgentGraphFactory graphFactory;
    private final MemoryService memoryService;

    public AgentService(Path dataDir, MemoryService memoryService) {
        this.auditLog = new ToolAuditLog(dataDir);
        this.toolRegistry = ToolRegistry.scan(new AgentTools());
        this.graphFactory = new AgentGraphFactory(toolRegistry, auditLog);
        this.memoryService = memoryService;
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** 提交一轮对话。history 为既有多轮消息快照（不含当前输入）。 */
    public boolean submit(RunRequest request, Listener listener) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        RunContext context = new RunContext(listener);
        if (!active.compareAndSet(null, context)) {
            busy.set(false);
            return false;
        }
        try {
            int toolCount = request.toolsEnabled() ? toolRegistry.size() : 0;
            Thread worker = new Thread(() -> {
                try {
                    new AgentRun(request, context, toolCount, compactor, graphFactory,
                            memoryService).execute();
                } finally {
                    finishRun(context);
                }
            }, "langgraph-agent");
            worker.setDaemon(true);
            context.worker = worker;
            worker.start();
            return true;
        } catch (RuntimeException e) {
            log.warn("启动 Agent 线程失败", e);
            active.compareAndSet(context, null);
            context.terminal.set(true);
            context.idle.complete(null);
            busy.set(false);
            return false;
        }
    }

    /** 尽力取消：取消生成器 + 中断工作线程。在途 HTTP 请求要等其自身超时。 */
    public void cancel() {
        RunContext context = active.get();
        if (context == null) {
            return;
        }
        context.cancelRequested.set(true);
        AsyncGenerator.Cancellable<NodeOutput<AgentExecutor.State>> c = context.cancellable;
        if (c != null) {
            try {
                c.cancel(true);
            } catch (RuntimeException e) {
                log.debug("取消流失败（继续中断线程）", e);
            }
        }
        Thread worker = context.worker;
        if (worker != null) {
            worker.interrupt();
        }
        if (busy.get() && active.get() == context) {
            scheduleStopWatchdog(context);
        }
    }

    /** 当前运行到达终态或 watchdog 放弃后回调；旧运行不会再阻塞后续提交。 */
    public void whenIdle(Runnable callback) {
        RunContext context = active.get();
        if (context == null) {
            callback.run();
            return;
        }
        context.idle.whenComplete((ignored, error) -> callback.run());
    }

    @Override
    public void close() {
        RunContext context = active.getAndSet(null);
        if (context != null) {
            context.cancelRequested.set(true);
            context.terminal.set(true);
            cancelContextWatchdog(context);
            AsyncGenerator.Cancellable<NodeOutput<AgentExecutor.State>> c = context.cancellable;
            if (c != null) {
                try {
                    c.cancel(true);
                } catch (RuntimeException e) {
                    log.debug("关闭时取消流失败", e);
                }
            }
            Thread worker = context.worker;
            if (worker != null) {
                worker.interrupt();
            }
            context.idle.complete(null);
        }
        watchdog.shutdownNow();
        busy.set(false);
    }

    /** 线程退出时统一收口：终态、流/线程引用、active/busy、idle。 */
    private void finishRun(RunContext context) {
        cancelContextWatchdog(context);
        context.terminal.set(true);
        context.cancellable = null;
        context.worker = null;
        if (active.compareAndSet(context, null)) {
            busy.set(false);
        }
        context.idle.complete(null);
    }

    private void scheduleStopWatchdog(RunContext context) {
        cancelContextWatchdog(context);
        context.stopWatchdog = watchdog.schedule(() -> {
            if (active.get() != context || context.terminal.get()) {
                return;
            }
            context.abandoned.set(true);
            context.listener.onError("已停止：后台请求仍在退出，稍后可重试。");
            AsyncGenerator.Cancellable<NodeOutput<AgentExecutor.State>> c = context.cancellable;
            if (c != null) {
                try {
                    c.cancel(true);
                } catch (RuntimeException e) {
                    log.debug("watchdog 取消流失败", e);
                }
            }
            Thread worker = context.worker;
            if (worker != null) {
                worker.interrupt();
            }
            // 旧线程可能仍卡在不可中断的 HTTP 调用里；释放提交锁，让新请求可以启动。
            if (active.compareAndSet(context, null)) {
                busy.set(false);
            }
            context.idle.complete(null);
        }, STOP_WATCHDOG_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelContextWatchdog(RunContext context) {
        ScheduledFuture<?> future = context.stopWatchdog;
        if (future != null) {
            future.cancel(false);
            context.stopWatchdog = null;
        }
    }

    /** 一次运行的独立状态：终态、取消标记、流句柄、线程与空闲信号。 */
    static final class RunContext {
        final AtomicBoolean terminal = new AtomicBoolean(false);
        final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        final CompletableFuture<Void> idle = new CompletableFuture<>();
        final Listener listener;
        volatile Thread worker;
        volatile AsyncGenerator.Cancellable<NodeOutput<AgentExecutor.State>> cancellable;
        volatile ScheduledFuture<?> stopWatchdog;

        RunContext(Listener delegate) {
            this.listener = new TerminalListener(delegate, terminal);
        }
    }

    /** 只允许一个终态回调；停止 watchdog 与工作线程的迟到回调不会互相覆盖。 */
    static final class TerminalListener implements Listener {
        private final Listener delegate;
        private final AtomicBoolean terminal;

        TerminalListener(Listener delegate, AtomicBoolean terminal) {
            this.delegate = delegate;
            this.terminal = terminal;
        }

        @Override
        public void onToken(String chunk) {
            if (!terminal.get()) {
                delegate.onToken(chunk);
            }
        }

        @Override
        public void onTrace(String event) {
            if (!terminal.get()) {
                delegate.onTrace(event);
            }
        }

        @Override
        public void onCompaction(String summary, int coveredCount) {
            if (!terminal.get()) {
                delegate.onCompaction(summary, coveredCount);
            }
        }

        @Override
        public void onComplete(String answer) {
            if (terminal.compareAndSet(false, true)) {
                delegate.onComplete(answer);
            }
        }

        @Override
        public void onError(String message) {
            if (terminal.compareAndSet(false, true)) {
                delegate.onError(message);
            }
        }
    }

}
