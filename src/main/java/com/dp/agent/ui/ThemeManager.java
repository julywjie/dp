package com.dp.agent.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Window;

import java.util.List;

/**
 * 主题应用与跨窗口同步：主窗口、对话框、弹层是不同 Window，
 * 需要各自挂上样式表与 theme-dark class。
 */
final class ThemeManager {

    private ThemeManager() {
    }

    static void apply(Parent sceneRoot, Button themeBtn, boolean dark) {
        themeBtn.setGraphic(Icons.icon(dark ? Icons.SUN : Icons.MOON, 17));
        if (sceneRoot == null) {
            return;
        }
        sceneRoot.getStyleClass().remove("theme-dark");
        if (dark) {
            sceneRoot.getStyleClass().add("theme-dark");
        }
    }

    static void syncToWindows(boolean dark, List<String> stylesheets) {
        for (Window window : Window.getWindows()) {
            Scene scene = window.getScene();
            if (scene == null || scene.getRoot() == null) {
                continue;
            }
            Parent sceneRoot = scene.getRoot();
            for (String stylesheet : stylesheets) {
                if (!scene.getStylesheets().contains(stylesheet)) {
                    scene.getStylesheets().add(stylesheet);
                }
            }
            boolean marked = sceneRoot.getStyleClass().contains("theme-dark");
            if (dark && !marked) {
                sceneRoot.getStyleClass().add("theme-dark");
            } else if (!dark && marked) {
                sceneRoot.getStyleClass().remove("theme-dark");
            }
        }
    }
}
