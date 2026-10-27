package com.dp.agent.core;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.config.ProviderType;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelFactoryTest {

    @AfterEach
    void clearCache() {
        ModelFactory.clearCache();
    }

    @Test
    void reusesModelForSameConfigurationAndKey() {
        ProviderConfig config = ProviderConfig.of("test", "Test",
                ProviderType.OPENAI_COMPATIBLE, "https://example.com/v1", "test-model");

        ChatModel first = ModelFactory.create(config, "key-1");
        ChatModel second = ModelFactory.create(config, "key-1");
        ChatModel otherKey = ModelFactory.create(config, "key-2");

        assertSame(first, second);
        assertNotSame(first, otherKey);
    }
}
