package com.dp.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingConfigCreatesDefaults() throws IOException {
        ConfigStore store = ConfigStore.forDirectory(dir);
        AppConfig config = store.load();
        assertFalse(config.getProviders().isEmpty());
        assertTrue(Files.exists(dir.resolve("config.json")));
    }

    @Test
    void saveAndReloadRoundTripAndKeepsBackup() throws IOException {
        ConfigStore store = ConfigStore.forDirectory(dir);
        AppConfig config = store.load();
        config.getProviders().get(0).setDisplayName("我的模型");
        store.save(config);
        store.save(config);

        AppConfig reloaded = ConfigStore.forDirectory(dir).load();
        assertEquals("我的模型", reloaded.getProviders().get(0).getDisplayName());
        assertTrue(Files.exists(dir.resolve("config.json.bak")));
    }

    @Test
    void corruptConfigIsQuarantinedAndReported() throws IOException {
        ConfigStore store = ConfigStore.forDirectory(dir);
        store.load();
        Files.writeString(dir.resolve("config.json"), "{ this is not json");

        ConfigStore reopened = ConfigStore.forDirectory(dir);
        AppConfig config = reopened.load();

        assertFalse(config.getProviders().isEmpty());
        assertNotNull(reopened.consumeRecoveryNote());
        assertNull(reopened.consumeRecoveryNote());
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("config.json.corrupt-")));
        }
    }

    @Test
    void apiKeysRoundTripEncrypted() throws IOException {
        ConfigStore store = ConfigStore.forDirectory(dir);
        AppConfig config = store.load();
        ProviderConfig p = config.getProviders().get(0);
        p.setApiKeyEnc(store.encryptKey("sk-test-key"));

        assertTrue(p.getApiKeyEnc().startsWith("gcm:"));
        assertEquals("sk-test-key", store.decryptKey(p));
    }
}
