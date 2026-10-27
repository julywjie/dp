package com.dp.agent.core;

import com.dp.agent.config.ToolApprovalPolicy;
import com.dp.agent.core.tool.ToolAuditLog;
import com.dp.agent.core.tool.ToolRegistry;
import com.dp.agent.core.tool.ToolRuntime;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;

import java.util.Map;

/** 把 RunRequest 装配成 LangGraph4j 的 ReAct 图；不持有任何运行期状态。 */
final class AgentGraphFactory {

    private final ToolRegistry toolRegistry;
    private final ToolAuditLog auditLog;

    AgentGraphFactory(ToolRegistry toolRegistry, ToolAuditLog auditLog) {
        this.toolRegistry = toolRegistry;
        this.auditLog = auditLog;
    }

    CompiledGraph<AgentExecutor.State> build(AgentService.RunRequest request, String systemPrompt,
                                             AgentService.Listener listener) throws Exception {
        AgentExecutor.Builder builder = AgentExecutor.builder()
                .systemMessage(new SystemMessage(systemPrompt));
        if (request.toolsEnabled()) {
            ToolRuntime runtime = new ToolRuntime(request.sessionId(),
                    request.confirmAllTools() ? ToolApprovalPolicy.ALWAYS : ToolApprovalPolicy.AUTO,
                    request.approvalGate(), auditLog, listener::onTrace);
            for (Map.Entry<ToolSpecification, ToolExecutor> entry
                    : toolRegistry.guardedExecutors(runtime).entrySet()) {
                builder.toolSpecification(entry.getKey(), entry.getValue());
            }
        }
        if (request.streamingEnabled()) {
            builder.chatModel(TruncationTolerantModels.wrap(
                    ModelFactory.createStreaming(request.provider(), request.apiKey())));
        } else {
            builder.chatModel(TruncationTolerantModels.wrap(
                    ModelFactory.create(request.provider(), request.apiKey())));
        }
        return builder.build().compile();
    }
}
