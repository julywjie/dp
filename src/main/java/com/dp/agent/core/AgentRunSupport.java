package com.dp.agent.core;

import com.dp.agent.core.context.TokenEstimator;
import com.dp.agent.core.tool.ToolCancelledException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;

import java.util.List;

/** Agent 单轮运行中的纯函数：提示词、检索 query、指标、轨迹与答案提取。 */
final class AgentRunSupport {

    private static final String BASE_PROMPT =
            "你是一个运行在 JavaFX 桌面端的智能助手。回答使用用户提问的语言，"
                    + "条理清晰、直接了当。涉及数学计算或获取当前时间时，优先调用可用的工具。";

    private AgentRunSupport() {
    }

    /** 用「上一轮助手回答 + 上一轮用户 + 当前问题」作为 query，改善追问场景。 */
    static String memoryQuery(AgentService.RunRequest request) {
        String lastAssistant = null;
        String lastUser = null;
        List<ChatMessage> history = request.history();
        for (int i = history.size() - 1; i >= 0
                && (lastAssistant == null || lastUser == null); i--) {
            ChatMessage message = history.get(i);
            if (lastAssistant == null && message instanceof AiMessage ai
                    && ai.text() != null && !ai.text().isBlank()) {
                lastAssistant = ai.text();
            } else if (lastUser == null && message instanceof UserMessage user) {
                lastUser = user.singleText();
            }
        }
        StringBuilder query = new StringBuilder();
        if (lastAssistant != null) {
            query.append(lastAssistant).append('\n');
        }
        if (lastUser != null) {
            query.append(lastUser).append('\n');
        }
        query.append(request.userText());
        String text = query.toString().strip();
        return text.length() <= 1200 ? text : text.substring(text.length() - 1200);
    }

    /** 记忆与摘要都折进唯一的 system message，避免多 SystemMessage 的提供商兼容问题。 */
    static String buildSystemPrompt(String summary, String memoryBlock) {
        StringBuilder prompt = new StringBuilder(BASE_PROMPT);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            prompt.append("\n\n").append(memoryBlock);
        }
        if (summary != null && !summary.isBlank()) {
            prompt.append("\n\n【较早对话摘要】\n").append(summary);
        }
        return prompt.toString();
    }

    static AgentMetrics buildMetrics(long startNanos, int steps, AgentExecutor.State state,
                                     String answer, int memoryHits) {
        int toolCalls = 0;
        int inputTokens = 0;
        if (state != null) {
            for (ChatMessage message : state.messages()) {
                inputTokens += TokenEstimator.estimateMessage(message);
                if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                    toolCalls += ai.toolExecutionRequests().size();
                }
            }
        }
        int outputTokens = TokenEstimator.estimate(answer);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new AgentMetrics(durationMs, steps, toolCalls, memoryHits,
                inputTokens, outputTokens);
    }

    /** 将 agent/action 节点输出翻译为用户可读轨迹。 */
    static void reportNode(NodeOutput<AgentExecutor.State> out, AgentService.Listener listener,
                           boolean streaming) {
        String node = out.node();
        List<ChatMessage> msgs = out.state().messages();
        if (msgs.isEmpty()) {
            return;
        }
        ChatMessage last = msgs.get(msgs.size() - 1);
        if ("agent".equals(node) && last instanceof AiMessage ai) {
            if (ai.hasToolExecutionRequests()) {
                ai.toolExecutionRequests().forEach(r ->
                        listener.onTrace("🔧 决定调用工具 " + r.name() + "(" + r.arguments() + ")"));
            } else if (!streaming) {
                listener.onTrace("🧠 生成回答 …"); // 流式时气泡本身即进度，此轨迹多余
            }
        } else if ("action".equals(node) && last instanceof ToolExecutionResultMessage tool) {
            listener.onTrace("✅ 工具 " + tool.toolName() + " 返回: " + truncate(tool.text(), 120));
        }
    }

    static String extractAnswer(AgentExecutor.State state) {
        if (state == null) {
            return "（无输出）";
        }
        List<ChatMessage> msgs = state.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof AiMessage ai
                    && ai.text() != null && !ai.text().isBlank()) {
                return ai.text();
            }
        }
        return state.finalResponse().orElse("（模型未返回文本，可能仅调用了工具）");
    }

    static boolean containsCancelled(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ToolCancelledException
                    || current instanceof InterruptedException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
