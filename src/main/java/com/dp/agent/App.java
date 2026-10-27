package com.dp.agent;

import com.dp.agent.config.AppConfig;
import com.dp.agent.config.ConfigStore;
import com.dp.agent.config.ProxySupport;
import com.dp.agent.ui.AppIcon;
import com.dp.agent.ui.ChatController;
import com.dp.agent.ui.ChatView;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * 应用入口：装配 配置存储 → 控制器 → 视图，生命周期与 Stage 一致。
 * 运行：mvn javafx:run
 */
public class App extends Application {

    private ChatController controller;

    @Override
    public void start(Stage stage) {
        ConfigStore store;
        try {
            store = ConfigStore.create();
        } catch (Exception e) {
            fatal("初始化配置目录失败: " + e.getMessage() + "\n请检查用户目录读写权限后重试。");
            return;
        }
        AppConfig config = store.load();
        ProxySupport.apply(config);
        try {
            controller = new ChatController(store, config);
        } catch (IOException e) {
            fatal("初始化会话存储失败: " + e.getMessage() + "\n请检查用户目录读写权限后重试。");
            return;
        }
        ChatView view = new ChatView(controller);
        controller.attach(view);
        stage.setScene(view.buildScene());
        view.attachStage(stage);
        AppIcon.apply(stage);
        stage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    private void fatal(String message) {
        new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE).showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
