package com.dp.agent.core.tool;

import com.dp.agent.config.ToolApprovalPolicy;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不访问网络，用假模型验证「ToolSpecification + 守卫执行器」能真正被 AgentExecutor 调用。 */
class ToolExecutionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void agentExecutorInvokesGuardedToolAndAudits() throws Exception {
        CountingTools tools = new CountingTools();
        ToolRegistry registry = new ToolRegistry().register(tools);
        ToolAuditLog audit = new ToolAuditLog(tempDir);
        List<String> traces = new ArrayList<>();
        ToolRuntime runtime = new ToolRuntime("session-it", ToolApprovalPolicy.AUTO,
                request -> ToolApprovalGate.Decision.APPROVED, audit, traces::add);

        AgentExecutor.Builder builder = AgentExecutor.builder()
                .systemMessage(new SystemMessage("你是助手"))
                .chatModel(new TwoStepModel());
        registry.guardedExecutors(runtime).forEach(builder::toolSpecification);
        CompiledGraph<AgentExecutor.State> graph = builder.build().compile();

        List<ChatMessage> input = new ArrayList<>();
        input.add(UserMessage.from("1+1 等于几？"));
        var stream = graph.stream(
                GraphInput.args(Map.of(MessagesState.MESSAGES_STATE, input)),
                RunnableConfig.empty());
        for (var ignored : stream) {
            // 消费整条 ReAct 流
        }

        assertEquals(1, tools.calls.get(), "工具应被调用一次");
        List<String> lines = Files.readAllLines(audit.file());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"tool\":\"calculator\""));
        assertTrue(lines.get(0).contains("\"success\":true"));
    }

    static final class CountingTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool("计算表达式")
        public String calculator(String expression) {
            calls.incrementAndGet();
            return "2";
        }
    }

    /** 第一次请求工具调用，第二次给出最终答案。 */
    static final class TwoStepModel implements ChatModel {
        private int step;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            if (step++ == 0) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("calculator")
                                .arguments("{\"expression\":\"1+1\"}")
                                .build()))
                        .build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("等于 2")).build();
        }
    }
}
