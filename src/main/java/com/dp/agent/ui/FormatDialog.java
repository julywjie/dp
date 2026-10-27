package com.dp.agent.ui;

import com.dp.agent.core.DataFormatter;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 数据格式化工具窗：左输入右结果，自动识别 JSON / XML。
 * 只做交互编排，格式化规则在 core/DataFormatter。
 */
public final class FormatDialog {

    private FormatDialog() {
    }

    public static void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("数据格式化工具");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(buildContent());
        dialog.getDialogPane().setPrefSize(880, 580);
        applyTheme(dialog.getDialogPane(), owner);
        dialog.setOnShown(e -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                AppIcon.apply(stage);
                WindowsTitleBar.apply(stage, owner);
            }
        });
        dialog.showAndWait();
    }

    /** 内容区独立构建，便于离屏渲染验收。 */
    static VBox buildContent() {
        return new Content().build();
    }

    private static void applyTheme(DialogPane pane, Window owner) {
        if (owner == null || owner.getScene() == null) {
            return;
        }
        pane.getStylesheets().addAll(owner.getScene().getStylesheets());
        if (owner.getScene().getRoot().getStyleClass().contains("theme-dark")) {
            pane.getStyleClass().add("theme-dark");
        }
    }

    private static final class Content {

        private static final String IDLE_HINT = "粘贴 JSON 或 XML 后点击「格式化」，或按 Ctrl+Enter";

        private final TextArea input = new TextArea();
        private final TextArea output = new TextArea();
        private final Label kindChip = new Label("自动检测");
        private final Label status = new Label(IDLE_HINT);

        VBox build() {
            configureAreas();

            Button format = new Button("格式化");
            format.getStyleClass().add("primary-button");
            format.setOnAction(e -> format());

            Button minify = new Button("压缩 JSON");
            minify.getStyleClass().add("ghost-button");
            minify.setOnAction(e -> minifyJson());

            Button copy = new Button("复制结果");
            copy.getStyleClass().add("ghost-button");
            copy.setOnAction(e -> copy());

            Button clear = new Button("清空");
            clear.getStyleClass().add("ghost-button");
            clear.setOnAction(e -> clear());

            kindChip.getStyleClass().add("format-kind-chip");
            HBox toolbar = new HBox(8, kindChip, spacer(), clear, copy, minify, format);
            toolbar.getStyleClass().add("format-bar");
            toolbar.setAlignment(Pos.CENTER_LEFT);

            VBox inputPane = pane("输入", input);
            VBox outputPane = pane("结果（只读）", output);
            HBox panes = new HBox(14, inputPane, outputPane);
            HBox.setHgrow(inputPane, Priority.ALWAYS);
            HBox.setHgrow(outputPane, Priority.ALWAYS);
            VBox.setVgrow(panes, Priority.ALWAYS);

            status.getStyleClass().add("format-status");
            VBox root = new VBox(10, toolbar, panes, status);
            root.getStyleClass().add("format-root");
            return root;
        }

        private void configureAreas() {
            input.setPromptText("在此粘贴 JSON 或 XML…");
            input.getStyleClass().add("format-area");
            input.setWrapText(false);
            input.textProperty().addListener((obs, old, text) -> updateKindChip(text));
            input.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER && (e.isControlDown() || e.isMetaDown())) {
                    e.consume();
                    format();
                }
            });

            output.setEditable(false);
            output.setWrapText(false);
            output.getStyleClass().addAll("format-area", "format-output");
        }

        private VBox pane(String caption, TextArea area) {
            Label label = new Label(caption);
            label.getStyleClass().add("format-caption");
            VBox box = new VBox(6, label, area);
            box.getStyleClass().add("format-pane");
            VBox.setVgrow(area, Priority.ALWAYS);
            return box;
        }

        private void format() {
            try {
                DataFormatter.Result result = DataFormatter.format(input.getText());
                output.setText(result.output());
                kindChip.setText(result.kind().name());
                long lines = result.output().lines().count();
                setStatus("已格式化 " + result.kind() + " · " + lines + " 行 · "
                        + result.output().length() + " 字符", "ok");
            } catch (DataFormatter.FormatException e) {
                output.clear();
                setStatus(e.getMessage(), "error");
            }
        }

        private void minifyJson() {
            try {
                DataFormatter.Kind type = DataFormatter.detect(input.getText());
                if (type != DataFormatter.Kind.JSON) {
                    setStatus("「压缩 JSON」仅支持 JSON 内容，当前输入是 XML。", "error");
                    return;
                }
                String compact = DataFormatter.minifyJson(input.getText());
                output.setText(compact);
                setStatus("已压缩 JSON · " + compact.length() + " 字符", "ok");
            } catch (DataFormatter.FormatException e) {
                output.clear();
                setStatus(e.getMessage(), "error");
            }
        }

        private void copy() {
            String text = output.getText();
            if (text == null || text.isBlank()) {
                setStatus("结果为空，请先格式化。", "error");
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("已复制到剪贴板 · " + text.length() + " 字符", "ok");
        }

        private void clear() {
            input.clear();
            output.clear();
            kindChip.setText("自动检测");
            kindChip.getStyleClass().remove("unknown");
            setStatus(IDLE_HINT, null);
        }

        private void updateKindChip(String text) {
            if (text == null || text.isBlank()) {
                kindChip.setText("自动检测");
                kindChip.getStyleClass().remove("unknown");
                return;
            }
            try {
                kindChip.setText(DataFormatter.detect(text).name());
                kindChip.getStyleClass().remove("unknown");
            } catch (DataFormatter.FormatException e) {
                kindChip.setText("未识别");
                if (!kindChip.getStyleClass().contains("unknown")) {
                    kindChip.getStyleClass().add("unknown");
                }
            }
        }

        private void setStatus(String text, String styleClass) {
            status.setText(text);
            status.getStyleClass().removeAll("ok", "error");
            if (styleClass != null) {
                status.getStyleClass().add(styleClass);
            }
        }

        private Region spacer() {
            Region region = new Region();
            HBox.setHgrow(region, Priority.ALWAYS);
            return region;
        }
    }
}
