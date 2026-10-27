package com.dp.agent.core;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.core.context.ContextCompactor;
import com.dp.agent.memory.MemoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单轮 Agent 执行：记忆 → 压缩 → 构图 → 流消费 → 终态。
 * 运行生命周期（active/busy/watchdog/finally）仍由 AgentService 持有。
 */
final class AgentRun {

    private static final Logger log = LoggerFactory.getLogger(AgentRun.class);
    /** 死循环熔断：agent/action 各算一步。 */
    private static final int MAX_STEPS = 60;

    private final AgentService.RunRequest request;
    private final AgentService.RunContext context;
    private final int toolCount;
    private final ContextCompactor compactor;
    private final AgentGraphFactory graphFactory;
    private final MemoryService memoryService;

    AgentRun(AgentService.RunRequest request, AgentService.RunContext context,
             int toolCount, ContextCompactor compactor, AgentGraphFactory graphFactory,
             MemoryService memoryService) {
        this.request = request;
        this.context = context;
        this.toolCount = toolCount;
        this.compactor = compactor;
        this.graphFactory = graphFactory;
        this.memoryService = memoryService;
    }

    void execute() {
        AgentService.Listener listener = context.listener;
        try {
            if (context.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                listener.onError("已手动停止。");
                return;
            }
            long runStart = System.nanoTime();
            ProviderConfig cfg = request.provider();
            listener.onTrace("模型 " + cfg.getModelName() + " · 组装 LangGraph 图 …");

            MemoryContext memory = retrieveMemory(listener);
            String memoryBlock = memory.text();
            List<ChatMessage> contextHistory = request.history();
            if (!request.appendUser() && !contextHistory.isEmpty()
                    && contextHistory.get(contextHistory.size() - 1) instanceof UserMessage) {
                // 重试时 history 已包含当前问题，估算时去掉以免重复计数
                contextHistory = contextHistory.subList(0, contextHistory.size() - 1);
            }
            ContextCompactor.Result compaction;
            try {
                compaction = compactor.compact(cfg, request.apiKey(), contextHistory,
                        request.summarizedCount(), request.summary(),
                        AgentRunSupport.buildSystemPrompt("", memoryBlock),
                        request.userText(), toolCount, listener::onTrace);
            } catch (ContextCompactor.InputTooLongException tooLong) {
                listener.onError(tooLong.getMessage());
                return;
            }
            if (context.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                listener.onError("已手动停止。");
                return;
            }
            if (compaction.covered() != request.summarizedCount()
                    || !compaction.summary().equals(request.summary())) {
                listener.onCompaction(compaction.summary(), compaction.covered());
            }

            CompiledGraph<AgentExecutor.State> graph = graphFactory.build(request,
                    AgentRunSupport.buildSystemPrompt(compaction.summary(), memoryBlock), listener);

            int covered = Math.max(0, Math.min(compaction.covered(), request.history().size()));
            List<ChatMessage> input = new ArrayList<>(
                    request.history().subList(covered, request.history().size()));
            if (request.appendUser()) {
                input.add(new UserMessage(request.userText()));
            }

            var stream = graph.stream(
                    GraphInput.args(Map.of(MessagesState.MESSAGES_STATE, input)),
                    RunnableConfig.empty());
            context.cancellable = stream;

            AgentExecutor.State lastState = null;
            int steps = 0;
            for (NodeOutput<AgentExecutor.State> out : stream) {
                if (context.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (out instanceof StreamingOutput<AgentExecutor.State> so) {
                    if (!so.isStreamingEnd() && so.chunk() != null && !so.chunk().isEmpty()) {
                        listener.onToken(so.chunk());
                    }
                    continue; // 流式项不计步数
                }
                lastState = out.state();
                steps++;
                AgentRunSupport.reportNode(out, listener, request.streamingEnabled());
                if (steps >= MAX_STEPS) {
                    stream.cancel(true);
                    listener.onError("已达到最大步数 " + MAX_STEPS + "，强制停止（疑似工具循环）。");
                    return;
                }
            }
            if (context.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                listener.onError("已手动停止。");
                return;
            }
            String answer = AgentRunSupport.extractAnswer(lastState);
            AgentMetrics metrics = AgentRunSupport.buildMetrics(runStart, steps, lastState, answer,
                    memory.hits());
            log.info("run session={} duration={}ms steps={} tools={} memories={} tokens~{}/{}",
                    request.sessionId(), metrics.durationMs(), metrics.steps(),
                    metrics.toolCalls(), metrics.memoryHits(),
                    metrics.estimatedInputTokens(), metrics.estimatedOutputTokens());
            listener.onTrace(metrics.traceLine());
            listener.onComplete(answer);
            if (!context.abandoned.get() && memoryService != null && answer != null
                    && !answer.startsWith("（模型未返回")) {
                memoryService.extractAsync(request.provider(), request.apiKey(),
                        request.userText(), answer, request.sessionId());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            listener.onError("已手动停止。");
        } catch (Throwable t) {
            if (AgentRunSupport.containsCancelled(t) || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                listener.onError("已手动停止。");
            } else {
                log.error("Agent 运行失败（session={}）", request.sessionId(), t);
                listener.onError(Errors.userMessage(t, "Agent 运行失败", 500));
            }
        }
    }

    /** 召回记忆；纯本地计算，失败时不影响本轮对话。 */
    private MemoryContext retrieveMemory(AgentService.Listener listener) {
        if (memoryService == null || !memoryService.isEnabled()) {
            return new MemoryContext("", 0);
        }
        try {
            MemoryService.Retrieved retrieved = memoryService.retrieve(
                    AgentRunSupport.memoryQuery(request));
            if (!retrieved.entries().isEmpty()) {
                listener.onTrace("🧠 记忆：召回 " + retrieved.entries().size() + " 条");
            }
            return new MemoryContext(retrieved.text(), retrieved.entries().size());
        } catch (RuntimeException e) {
            log.warn("记忆召回失败: {}", e.getMessage());
            return new MemoryContext("", 0);
        }
    }

    private record MemoryContext(String text, int hits) {
    }
}
