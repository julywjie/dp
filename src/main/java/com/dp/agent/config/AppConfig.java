package com.dp.agent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用级配置：提供商列表 + 当前选中项 + Agent 工具开关。持久化为 JSON。
 */
public class AppConfig {

    /** 全局出网代理；修改后对新建的 HTTP 客户端生效。 */
    public enum ProxyMode {
        NONE("不使用"),
        HTTP("HTTP 代理"),
        SOCKS("SOCKS 代理");

        private final String label;

        ProxyMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private List<ProviderConfig> providers = new ArrayList<>();
    private String selectedProviderId = "";
    private boolean toolsEnabled = true;
    private boolean streamingEnabled = true;
    private boolean darkTheme = false;
    private ToolApprovalPolicy toolApprovalPolicy = ToolApprovalPolicy.AUTO;
    private ProxyMode proxyMode = ProxyMode.NONE;
    private String proxyHost = "";
    private int proxyPort;

    public Optional<ProviderConfig> selected() {
        return providers.stream()
                .filter(p -> p.getId().equals(selectedProviderId))
                .findFirst();
    }

    /** 深拷贝：设置对话框只在保存成功后把草稿回写到运行配置。 */
    public AppConfig copy() {
        AppConfig c = new AppConfig();
        List<ProviderConfig> copies = new ArrayList<>();
        for (ProviderConfig p : providers) {
            if (p != null) {
                copies.add(p.copy());
            }
        }
        c.providers = copies;
        c.selectedProviderId = selectedProviderId;
        c.toolsEnabled = toolsEnabled;
        c.streamingEnabled = streamingEnabled;
        c.darkTheme = darkTheme;
        c.toolApprovalPolicy = toolApprovalPolicy;
        c.proxyMode = proxyMode;
        c.proxyHost = proxyHost;
        c.proxyPort = proxyPort;
        return c;
    }

    /** 用草稿覆盖运行配置（保留对象身份，控制器持有同一个引用）。 */
    public void applyFrom(AppConfig draft) {
        if (draft == null) {
            return;
        }
        this.providers = new ArrayList<>(draft.getProviders());
        this.selectedProviderId = draft.getSelectedProviderId();
        this.toolsEnabled = draft.isToolsEnabled();
        this.streamingEnabled = draft.isStreamingEnabled();
        this.darkTheme = draft.isDarkTheme();
        this.toolApprovalPolicy = draft.getToolApprovalPolicy();
        this.proxyMode = draft.getProxyMode();
        this.proxyHost = draft.getProxyHost();
        this.proxyPort = draft.getProxyPort();
    }

    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public String getSelectedProviderId() { return selectedProviderId; }
    public void setSelectedProviderId(String selectedProviderId) { this.selectedProviderId = selectedProviderId; }

    public boolean isToolsEnabled() { return toolsEnabled; }
    public void setToolsEnabled(boolean toolsEnabled) { this.toolsEnabled = toolsEnabled; }

    public boolean isStreamingEnabled() { return streamingEnabled; }
    public void setStreamingEnabled(boolean streamingEnabled) { this.streamingEnabled = streamingEnabled; }

    public boolean isDarkTheme() { return darkTheme; }
    public void setDarkTheme(boolean darkTheme) { this.darkTheme = darkTheme; }

    public ToolApprovalPolicy getToolApprovalPolicy() {
        return toolApprovalPolicy == null ? ToolApprovalPolicy.AUTO : toolApprovalPolicy;
    }

    public void setToolApprovalPolicy(ToolApprovalPolicy toolApprovalPolicy) {
        this.toolApprovalPolicy = toolApprovalPolicy == null
                ? ToolApprovalPolicy.AUTO : toolApprovalPolicy;
    }

    public ProxyMode getProxyMode() {
        return proxyMode == null ? ProxyMode.NONE : proxyMode;
    }

    public void setProxyMode(ProxyMode proxyMode) {
        this.proxyMode = proxyMode == null ? ProxyMode.NONE : proxyMode;
    }

    public String getProxyHost() {
        return proxyHost == null ? "" : proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }
}
