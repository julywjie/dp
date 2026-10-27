package com.dp.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderValidatorTest {

    private static ProviderConfig valid() {
        ProviderConfig p = ProviderConfig.of("test", "测试提供商", ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", "test-model");
        p.setMaxTokens(2048);
        p.setTimeoutSeconds(120);
        p.setTemperature(0.7);
        p.setContextWindow(8192);
        return p;
    }

    @Test
    void acceptsValidConfig() {
        assertTrue(ProviderValidator.validate(List.of(valid())).isEmpty());
    }

    @Test
    void acceptsLocalhostHttp() {
        ProviderConfig p = valid();
        p.setBaseUrl("http://localhost:11434/v1");
        assertTrue(ProviderValidator.validate(List.of(p)).isEmpty());
    }

    @Test
    void rejectsBlankNameAndModel() {
        ProviderConfig p = valid();
        p.setDisplayName("  ");
        p.setModelName("");
        List<String> errors = ProviderValidator.validate(List.of(p));
        assertEquals(2, errors.size());
    }

    @Test
    void rejectsNonHttpUrl() {
        ProviderConfig p = valid();
        p.setBaseUrl("file:///etc/passwd");
        assertEquals(1, ProviderValidator.validate(List.of(p)).size());
    }

    @Test
    void rejectsNaNOrOutOfRangeNumbers() {
        ProviderConfig p = valid();
        p.setTemperature(Double.NaN);
        p.setMaxTokens(0);
        p.setTimeoutSeconds(0);
        List<String> errors = ProviderValidator.validate(List.of(p));
        assertEquals(3, errors.size());
    }

    @Test
    void rejectsContextWindowSmallerThanTokens() {
        ProviderConfig p = valid();
        p.setMaxTokens(8192);
        p.setContextWindow(8192);
        assertEquals(1, ProviderValidator.validate(List.of(p)).size());
    }

    @Test
    void emptyProviderListIsAllowed() {
        assertTrue(ProviderValidator.validate(List.of()).isEmpty());
    }
}
