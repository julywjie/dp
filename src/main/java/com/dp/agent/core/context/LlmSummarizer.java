package com.dp.agent.core.context;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.core.ModelFactory;
import dev.langchain4j.model.chat.ChatModel;

/** 用当前提供商的短输出/短超时模型生成上下文摘要。 */
public final class LlmSummarizer implements ContextCompactor.Summarizer {

    @Override
    public String summarize(ProviderConfig cfg, String apiKey, String prompt) {
        ChatModel model = ModelFactory.createSummaryModel(cfg, apiKey);
        return model.chat(prompt);
    }
}
