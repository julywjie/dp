package com.dp.agent.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * 统一的应用内对话框：带 owner、复用主窗口样式表与深浅主题。
 * 避免深色主题下突然弹出未着色的系统浅色 Alert，或弹窗跑到主窗口后面。
 */
final class UiDialogs {

    private UiDialogs() {
    }

    static boolean confirm(Window owner, String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        style(alert, owner);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    static void warn(Window owner, String title, String message) {
        show(Alert.AlertType.WARNING, owner, title, message);
    }

    static void error(Window owner, String title, String message) {
        show(Alert.AlertType.ERROR, owner, title, message);
    }

    private static void show(Alert.AlertType type, Window owner, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        style(alert, owner);
        alert.showAndWait();
    }

    private static void style(Dialog<?> dialog, Window owner) {
        applyTheme(dialog, owner);
    }

    /** 供自定义 Dialog 复用：owner、样式表、深浅主题、图标与标题栏。 */
    static void applyTheme(Dialog<?> dialog, Window owner) {
        if (owner != null) {
            dialog.initOwner(owner);
        }
        if (owner == null || owner.getScene() == null) {
            return;
        }
        dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        if (owner.getScene().getRoot().getStyleClass().contains("theme-dark")) {
            dialog.getDialogPane().getStyleClass().add("theme-dark");
        }
        dialog.setOnShown(e -> {
            if (dialog.getDialogPane().getScene().getWindow()
                    instanceof javafx.stage.Stage stage) {
                AppIcon.apply(stage);
                WindowsTitleBar.apply(stage, owner);
            }
        });
    }
}
