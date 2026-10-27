package com.dp.agent.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * 窗口顶部的轻量提示条：用于“配置保存失败 / 未配置 Key / 已切换模型”等
 * 与当前页面无关的状态，避免只写进聊天流、在发现页完全看不见。
 */
final class Toast {

    private static final Duration VISIBLE_TIME = Duration.seconds(3.6);

    private final Label label = new Label();
    private final StackPane layer = new StackPane(label);
    private final FadeTransition fade = new FadeTransition(Duration.millis(180), label);
    private final PauseTransition wait = new PauseTransition(VISIBLE_TIME);

    Toast() {
        label.getStyleClass().add("toast");
        label.setWrapText(true);
        label.setMaxWidth(620);
        label.setVisible(false);
        label.setMouseTransparent(true);
        layer.setAlignment(Pos.TOP_CENTER);
        layer.setPadding(new Insets(64, 18, 0, 18));
        layer.setPickOnBounds(false);
        layer.setMouseTransparent(true);
        fade.setOnFinished(e -> label.setVisible(false));
        wait.setOnFinished(e -> {
            fade.setFromValue(label.getOpacity());
            fade.setToValue(0);
            fade.play();
        });
    }

    Node node() {
        return layer;
    }

    void show(String message, boolean error) {
        if (message == null || message.isBlank()) {
            return;
        }
        label.getStyleClass().remove("toast-error");
        if (error) {
            label.getStyleClass().add("toast-error");
        }
        label.setText(message);
        fade.stop();
        wait.stop();
        label.setVisible(true);
        label.setOpacity(1);
        wait.playFromStart();
    }
}
