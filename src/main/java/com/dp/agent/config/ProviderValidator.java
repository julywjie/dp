package com.dp.agent.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 提供商配置的纯规则校验（不依赖 JavaFX），供设置对话框与测试共用。
 * 目的是把非法值挡在 ModelFactory 之前，避免 0 超时、NaN 温度之类的运行期异常。
 */
public final class ProviderValidator {

    private static final int MAX_TOKENS_FLOOR = 1;
    private static final int MAX_TOKENS_CEILING = 1_000_000;
    private static final int TIMEOUT_FLOOR = 1;
    private static final int TIMEOUT_CEILING = 600;
    private static final int CONTEXT_WINDOW_MIN = 1024;
    private static final int CONTEXT_WINDOW_MARGIN = 512;

    private ProviderValidator() {
    }

    public static List<String> validate(List<ProviderConfig> providers) {
        List<String> errors = new ArrayList<>();
        if (providers == null || providers.isEmpty()) {
            return errors; // 允许清空；顶栏会提示未选择提供商
        }
        for (ProviderConfig p : providers) {
            if (p == null) {
                continue;
            }
            validateOne(p, errors);
        }
        return errors;
    }

    private static void validateOne(ProviderConfig p, List<String> errors) {
        String name = label(p);
        if (isBlank(p.getDisplayName())) {
            errors.add(name + "：名称不能为空");
        }
        if (isBlank(p.getModelName())) {
            errors.add(name + "：模型名不能为空");
        }
        if (!isValidUrl(p.getBaseUrl())) {
            errors.add(name + "：Base URL 必须是 http/https 地址");
        }
        if (p.getMaxTokens() < MAX_TOKENS_FLOOR || p.getMaxTokens() > MAX_TOKENS_CEILING) {
            errors.add(name + "：最大 Tokens 需在 " + MAX_TOKENS_FLOOR + "–"
                    + MAX_TOKENS_CEILING + " 之间");
        }
        if (p.getTimeoutSeconds() < TIMEOUT_FLOOR || p.getTimeoutSeconds() > TIMEOUT_CEILING) {
            errors.add(name + "：超时需在 " + TIMEOUT_FLOOR + "–" + TIMEOUT_CEILING + " 秒之间");
        }
        double temperature = p.getTemperature();
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            errors.add(name + "：Temperature 需在 0–2 之间");
        }
        if (p.getContextWindow() > 0 && p.getContextWindow() < CONTEXT_WINDOW_MIN) {
            errors.add(name + "：上下文窗口至少 " + CONTEXT_WINDOW_MIN + " tokens");
        }
        if (p.effectiveContextWindow() <= p.getMaxTokens() + CONTEXT_WINDOW_MARGIN) {
            errors.add(name + "：上下文窗口必须大于「最大 Tokens + " + CONTEXT_WINDOW_MARGIN + "」");
        }
    }

    private static boolean isValidUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String label(ProviderConfig p) {
        return isBlank(p.getDisplayName()) ? "未命名提供商" : "「" + p.getDisplayName() + "」";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
