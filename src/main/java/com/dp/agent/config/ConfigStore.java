package com.dp.agent.config;

import com.dp.agent.storage.JsonFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 配置持久化：JSON 文件 + 同目录 secret.key（AES 密钥）。
 * 位置优先 %APPDATA%\dp-agent（Windows），回退 ~/.dp-agent。
 * 写入采用"临时文件 + 原子替换"，避免进程中断导致配置文件损坏。
 */
public final class ConfigStore {

    private static final String DIR_NAME = "dp-agent";
    private static final String CONFIG_FILE = "config.json";

    private final Path dir;
    private final CryptoUtil crypto;
    /** 上次 load 发现损坏文件时给出的一次性提示，供 UI 在启动时展示。 */
    private volatile String recoveryNote;
    /** 宽容反序列化：新增字段/枚举不会让旧配置文件整体失效、清空 Key。 */
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

    private ConfigStore(Path dir, CryptoUtil crypto) {
        this.dir = dir;
        this.crypto = crypto;
    }

    public static ConfigStore create() throws IOException {
        return forDirectory(resolveDataDir());
    }

    /** 供测试指定数据目录；首次调用会生成该目录下的 secret.key。 */
    static ConfigStore forDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        return new ConfigStore(dir, CryptoUtil.forDirectory(dir));
    }

    private static Path resolveDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, DIR_NAME);
        }
        return Path.of(System.getProperty("user.home"), "." + DIR_NAME);
    }

    public Path getDir() {
        return dir;
    }

    public AppConfig load() {
        Path file = dir.resolve(CONFIG_FILE);
        if (!Files.exists(file)) {
            AppConfig def = defaultConfig();
            try {
                save(def);
            } catch (IOException e) {
                // 首次保存失败不致命：内存里继续用，下次再试
            }
            return def;
        }
        try {
            return normalize(mapper.readValue(file.toFile(), AppConfig.class));
        } catch (Exception e) {
            recoverCorrupt(file, e);
            return normalize(defaultConfig());
        }
    }

    /** 损坏的配置不静默丢弃：改名保留，下次保存不会覆盖它。 */
    private void recoverCorrupt(Path file, Exception cause) {
        Path quarantine = file.resolveSibling(
                CONFIG_FILE + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, quarantine);
            recoveryNote = "配置文件读取失败，已备份为 " + quarantine.getFileName()
                    + "，当前使用默认配置。";
        } catch (IOException moveFailed) {
            recoveryNote = "配置文件读取失败，且无法备份：" + moveFailed.getMessage();
        }
        System.err.println("[dp-agent] 配置损坏，已隔离: " + cause.getMessage());
    }

    /** 启动时的一次性提示；没有发生过损坏则返回 null。 */
    public String consumeRecoveryNote() {
        String note = recoveryNote;
        recoveryNote = null;
        return note;
    }

    /** 修补旧配置/手工编辑造成的空字段，避免后续 NPE。 */
    private AppConfig normalize(AppConfig cfg) {
        List<ProviderConfig> providers = new ArrayList<>();
        List<ProviderConfig> loaded = cfg.getProviders() == null ? List.of() : cfg.getProviders();
        for (ProviderConfig p : loaded) {
            if (p == null) {
                continue;
            }
            if (p.getType() == null) {
                p.setType(ProviderType.OPENAI_COMPATIBLE);
            }
            if (p.getId() == null || p.getId().isBlank()) {
                p.setId(UUID.randomUUID().toString());
            }
            providers.add(p);
        }
        cfg.setProviders(providers);
        // 选中的提供商可能已被删除：回退到第一项，避免顶栏空白。
        String selected = cfg.getSelectedProviderId();
        boolean exists = selected != null
                && providers.stream().anyMatch(p -> selected.equals(p.getId()));
        if (!exists) {
            cfg.setSelectedProviderId(providers.isEmpty() ? "" : providers.get(0).getId());
        }
        return cfg;
    }

    public void save(AppConfig config) throws IOException {
        Path file = dir.resolve(CONFIG_FILE);
        JsonFiles.backup(file);
        JsonFiles.writeAtomically(file, mapper, config);
    }

    public String decryptKey(ProviderConfig p) {
        return crypto.decrypt(p.getApiKeyEnc());
    }

    public String encryptKey(String plain) {
        return crypto.encrypt(plain);
    }

    /** 内置预设：主流 OpenAI 兼容端点 + Anthropic 原生。 */
    static AppConfig defaultConfig() {
        AppConfig cfg = new AppConfig();
        cfg.setProviders(new ArrayList<>(List.of(
                ProviderConfig.of("deepseek", "DeepSeek", ProviderType.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com/v1", "deepseek-chat"),
                ProviderConfig.of("qwen", "通义千问", ProviderType.OPENAI_COMPATIBLE,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                ProviderConfig.of("glm", "智谱 GLM", ProviderType.OPENAI_COMPATIBLE,
                        "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
                ProviderConfig.of("kimi", "Kimi (月之暗面)", ProviderType.OPENAI_COMPATIBLE,
                        "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
                ProviderConfig.of("openai", "OpenAI", ProviderType.OPENAI_COMPATIBLE,
                        "https://api.openai.com/v1", "gpt-4o-mini"),
                ProviderConfig.of("openrouter", "OpenRouter", ProviderType.OPENAI_COMPATIBLE,
                        "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
                ProviderConfig.of("ollama", "Ollama 本地", ProviderType.OPENAI_COMPATIBLE,
                        "http://localhost:11434/v1", "qwen2.5:7b"),
                ProviderConfig.of("anthropic", "Anthropic Claude", ProviderType.ANTHROPIC,
                        "https://api.anthropic.com", "claude-sonnet-4-6")
        )));
        cfg.setSelectedProviderId("deepseek");
        cfg.setToolsEnabled(true);
        return cfg;
    }
}
