package com.dp.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TruncationTolerantModelsTest {

    /** 接口方法全为 default，不可用 lambda；以匿名类 stub 待测模型。 */
    private static ChatModel modelReturning(ChatResponse fixed) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return fixed;
            }
        };
    }

    private static ChatResponse response(AiMessage ai, FinishReason reason, TokenUsage usage) {
        return ChatResponse.builder()
                .aiMessage(ai)
                .tokenUsage(usage)
                .finishReason(reason)
                .build();
    }

    @Test
    void lengthTextResponseBecomesStopWithNotice() {
        ChatModel model = TruncationTolerantModels.wrap(
                modelReturning(response(AiMessage.from("部分答案"), FinishReason.LENGTH,
                        new TokenUsage(100, 50))));

        ChatResponse out = model.chat((ChatRequest) null);

        assertEquals(FinishReason.STOP, out.finishReason());
        assertTrue(out.aiMessage().text().startsWith("部分答案"));
        assertTrue(out.aiMessage().text().contains("被截断"));
        assertEquals(new TokenUsage(100, 50), out.tokenUsage());
    }

    @Test
    void stopResponsePassesThroughUntouched() {
        ChatResponse original = response(AiMessage.from("完整答案"), FinishReason.STOP, null);

        ChatResponse out = TruncationTolerantModels.wrap(modelReturning(original))
                .chat((ChatRequest) null);

        assertSame(original, out);
    }

    @Test
    void lengthToolCallKeepsArgumentsAndRequest() {
        ToolExecutionRequest truncated = ToolExecutionRequest.builder()
                .id("1").name("weather").arguments("{\"city\":\"北") // JSON 被截断
                .build();
        AiMessage ai = AiMessage.from(List.of(truncated));

        ChatResponse out = TruncationTolerantModels.wrap(
                        modelReturning(response(ai, FinishReason.LENGTH, null)))
                .chat((ChatRequest) null);

        assertEquals(FinishReason.STOP, out.finishReason());
        assertEquals(List.of(truncated), out.aiMessage().toolExecutionRequests());
    }

    @Test
    void streamingHandlerNormalizesCompleteResponseOnly() {
        ChatResponse truncated = response(AiMessage.from("半截"), FinishReason.LENGTH, null);
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        StringBuilder partial = new StringBuilder();

        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("半");
                handler.onPartialResponse("截");
                handler.onCompleteResponse(truncated);
            }
        };
        TruncationTolerantModels.wrap(delegate).chat((ChatRequest) null,
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        partial.append(token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        completed.set(completeResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        throw new AssertionError("不应报错", error);
                    }
                });

        assertEquals("半截", partial.toString());
        assertEquals(FinishReason.STOP, completed.get().finishReason());
        assertTrue(completed.get().aiMessage().text().contains("被截断"));
    }
}
