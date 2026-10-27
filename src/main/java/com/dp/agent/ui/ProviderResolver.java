package com.dp.agent.ui;

import com.dp.agent.config.ConfigStore;
import com.dp.agent.config.ProviderConfig;

import java.net.URI;
import java.util.function.Supplier;

/** 统一解析当前提供商与 API Key，避免发送、重试、总结各自复制校验逻辑。 */
final class ProviderResolver {

    record Access(ProviderConfig provider, String apiKey, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private final Supplier<ProviderConfig> selected;
    private final ConfigStore store;

    ProviderResolver(Supplier<ProviderConfig> selected, ConfigStore store) {
        this.selected = selected;
        this.store = store;
    }

    Access resolve() {
        ProviderConfig provider = selected.get();
        if (provider == null) {
            return new Access(null, "", "请先在设置中添加并选择一个模型提供商。");
        }
        String apiKey = store.decryptKey(provider);
        if (apiKey.isBlank() && !isLocalEndpoint(provider.getBaseUrl())) {
            return new Access(provider, apiKey,
                    "【" + provider.getDisplayName()
                            + "】尚未配置 API Key，请点击 ⚙ 设置 填写。");
        }
        return new Access(provider, apiKey, null);
    }

    /** localhost / 127.0.0.1 / ::1 的 Ollama、vLLM 等本地端点允许空 Key。 */
    static boolean isLocalEndpoint(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1") || host.equals("::1"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
