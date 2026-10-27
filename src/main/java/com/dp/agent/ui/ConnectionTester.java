package com.dp.agent.ui;

import com.dp.agent.config.ProviderConfig;
import com.dp.agent.core.Errors;
import com.dp.agent.core.ModelFactory;
import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 提供商连通性测试：后台线程发一条最小请求，结果回到 FX 线程回调。
 * 不持有任何控件，由调用方决定如何展示结果。
 */
final class ConnectionTester {

    interface Listener {

        /** @param message 展示用结果文本；@param success 是否连接成功 */
        void onResult(String message, boolean success);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "settings-test");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 异步测试 {@code config}（调用方负责把 maxTokens/timeout 调成测试值）。
     * 回调总是在 FX 应用线程执行；对话框可能已关闭，由监听方自行判断。
     */
    void test(ProviderConfig config, String apiKey, Listener listener) {
        executor.submit(() -> {
            String message;
            boolean success;
            try {
                ModelFactory.create(config, apiKey).chat("ping");
                message = "连接成功";
                success = true;
            } catch (Exception e) {
                message = "失败：" + Errors.userMessage(e, "连接失败", 180);
                success = false;
            }
            String result = message;
            boolean ok = success;
            Platform.runLater(() -> listener.onResult(result, ok));
        });
    }

    void shutdownNow() {
        executor.shutdownNow();
    }
}
