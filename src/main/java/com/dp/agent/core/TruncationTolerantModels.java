package com.dp.agent.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

/**
 * 模型适配层装饰器：把「输出达到 maxTokens 上限」的截断响应（finishReason=LENGTH）
 * 归一化为 STOP，并在文本尾部附加截断说明。
 *
 * 背景：LangGraph4j AgentExecutor 对非 STOP/TOOL_EXECUTION 的结束原因直接抛
 * IllegalStateException，会把一次可恢复的截断变成整轮失败（用户已看到的流式
 * 文字随之作废）。推理型模型的隐藏思考 token 同样计入 maxTokens，默认 2048
 * 很容易触发。 LENGTH 且带工具调用时参数同样被截断：归一化后仍走工具分支，
 * 截断的 JSON 参数由 GuardedToolExecutor 转为 isError 结果，ReAct 自我恢复。
 *
 * CONTENT_FILTER / OTHER 不归一化：语义不明，保持原有报错路径。
 */
final class TruncationTolerantModels {

    private static final String TRUNCATION_NOTICE =
            "\n\n---\n（输出达到长度上限被截断，可在 设置 → 最大输出 中调大后重试）";

    private TruncationTolerantModels() {
    }

    static ChatModel wrap(ChatModel delegate) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return normalize(delegate.chat(request));
            }
        };
    }

    static StreamingChatModel wrap(StreamingChatModel delegate) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                delegate.chat(request, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        handler.onPartialResponse(token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        handler.onCompleteResponse(normalize(completeResponse));
                    }

                    @Override
                    public void onError(Throwable error) {
                        handler.onError(error);
                    }
                });
            }
        };
    }

    /** LENGTH → STOP；纯文本响应追加截断说明，其余字段（usage 等）原样保留。 */
    private static ChatResponse normalize(ChatResponse response) {
        if (response == null || response.finishReason() != FinishReason.LENGTH) {
            return response;
        }
        AiMessage ai = response.aiMessage();
        AiMessage normalized = ai != null && ai.text() != null && !ai.text().isBlank()
                && !ai.hasToolExecutionRequests()
                ? ai.withText(ai.text() + TRUNCATION_NOTICE)
                : ai;
        ChatResponseMetadata metadata = response.metadata();
        ChatResponseMetadata normalizedMetadata = metadata == null
                ? ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build()
                : metadata.toBuilder().finishReason(FinishReason.STOP).build();
        return response.toBuilder()
                .aiMessage(normalized)
                .metadata(normalizedMetadata) // finishReason 随 metadata 生效，二者互斥
                .build();
    }
}
