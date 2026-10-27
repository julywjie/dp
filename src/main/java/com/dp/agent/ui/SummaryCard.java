package com.dp.agent.ui;

import com.dp.agent.ui.markdown.MarkdownRenderer;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** 发现页列表上方的内联总结卡：生成中骨架、结果 Markdown、复制与收起。 */
final class SummaryCard {

    private static final double BODY_WIDTH = 620;

    private final VBox root = new VBox(8);
    private final Label meta = new Label();
    private final Label badge = new Label("仅标题");
    private final Label note = new Label();
    private final VBox body = new VBox(6);
    private final Button open = new Button();
    private final Button copy = new Button();
    private String rawText = "";

    SummaryCard() {
        this("AI 总结");
    }

    SummaryCard(String titleText) {
        StackPane orb = new StackPane(Icons.icon(Icons.SPARK, 14));
        orb.getStyleClass().add("summary-orb");

        Label title = new Label(titleText);
        title.getStyleClass().add("summary-title");
        meta.getStyleClass().add("summary-meta");

        badge.getStyleClass().add("summary-badge");
        badge.setVisible(false);
        badge.setManaged(false);

        note.getStyleClass().add("summary-note");
        note.setWrapText(true);
        note.setMaxWidth(BODY_WIDTH);
        note.setVisible(false);
        note.setManaged(false);

        open.setGraphic(Icons.icon(Icons.OPEN_EXTERNAL, 13));
        open.getStyleClass().add("summary-action");
        open.setTooltip(new Tooltip("打开原文"));
        open.setAccessibleText("打开原文");
        open.setVisible(false);
        open.setManaged(false);

        copy.setGraphic(Icons.icon(Icons.COPY, 13));
        copy.getStyleClass().add("summary-action");
        copy.setTooltip(new Tooltip("复制总结"));
        copy.setAccessibleText("复制总结");
        copy.setOnAction(e -> copyText());

        Button close = new Button();
        close.setGraphic(Icons.icon(Icons.CLOSE, 13));
        close.getStyleClass().add("summary-action");
        close.setTooltip(new Tooltip("收起总结"));
        close.setAccessibleText("收起总结");
        close.setOnAction(e -> hide());

        HBox header = new HBox(8, orb, title, meta, badge, spacer(), open, copy, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("summary-header");
        root.getChildren().addAll(header, body);
        root.getStyleClass().add("summary-card");
        root.setMaxWidth(Double.MAX_VALUE);
        hide();
    }

    Node node() {
        return root;
    }

    void showLoading(String metaText, String hintText) {
        rawText = "";
        meta.setText(metaText);
        setCopyVisible(false);
        setOpenVisible(null);
        setBadgeVisible(false);
        setNote("");
        body.getChildren().clear();

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.getStyleClass().add("summary-spinner");
        Label hint = new Label(hintText);
        hint.getStyleClass().add("summary-meta");
        HBox status = new HBox(8, spinner, hint);
        status.setAlignment(Pos.CENTER_LEFT);

        VBox skeleton = new VBox(6);
        for (double width : new double[]{420, 560, 330}) {
            Region line = new Region();
            line.getStyleClass().add("summary-skeleton-line");
            line.setPrefWidth(width);
            line.setMaxWidth(width);
            skeleton.getChildren().add(line);
        }
        body.getChildren().addAll(status, skeleton);
        show();
    }

    void showResult(String metaText, String text, String modelName) {
        showResult(metaText, text, modelName, null, false, "");
    }

    void showResult(String metaText, String text, String modelName,
                    String openUrl, boolean fallback, String noteText) {
        rawText = text;
        String model = modelName == null || modelName.isBlank() ? "当前模型" : modelName;
        meta.setText(metaText + " · " + model);
        setCopyVisible(true);
        setOpenVisible(openUrl);
        setBadgeVisible(fallback);
        setNote(noteText);

        body.getChildren().clear();
        if (note.isVisible()) {
            body.getChildren().add(note);
        }
        body.getChildren().add(MarkdownRenderer.render(text, BODY_WIDTH));
        show();
    }

    void showError(String metaText, String message) {
        rawText = "";
        meta.setText(metaText);
        setCopyVisible(false);
        setOpenVisible(null);
        setBadgeVisible(false);
        setNote("");
        Label error = new Label(message);
        error.setWrapText(true);
        error.setMaxWidth(BODY_WIDTH);
        error.getStyleClass().add("summary-error");
        body.getChildren().setAll(error);
        show();
    }

    void hide() {
        root.setVisible(false);
        root.setManaged(false);
        body.getChildren().clear();
        rawText = "";
        setOpenVisible(null);
        setBadgeVisible(false);
        setNote("");
    }

    private void setOpenVisible(String url) {
        boolean visible = url != null && !url.isBlank();
        open.setVisible(visible);
        open.setManaged(visible);
        open.setOnAction(e -> {
            if (visible) {
                LinkOpener.open(url);
            }
        });
    }

    private void setBadgeVisible(boolean visible) {
        badge.setVisible(visible);
        badge.setManaged(visible);
    }

    private void setNote(String text) {
        boolean visible = text != null && !text.isBlank();
        note.setText(visible ? text : "");
        note.setVisible(visible);
        note.setManaged(visible);
    }

    private void show() {
        boolean wasVisible = root.isVisible();
        root.setVisible(true);
        root.setManaged(true);
        if (!wasVisible) {
            Motion.enter(root);
        }
    }

    private void setCopyVisible(boolean visible) {
        copy.setVisible(visible);
        copy.setManaged(visible);
    }

    private void copyText() {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(rawText);
        Clipboard.getSystemClipboard().setContent(content);
        copy.setGraphic(Icons.icon(Icons.CHECK, 13));
        PauseTransition reset = new PauseTransition(Duration.seconds(1.2));
        reset.setOnFinished(e -> copy.setGraphic(Icons.icon(Icons.COPY, 13)));
        reset.play();
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
