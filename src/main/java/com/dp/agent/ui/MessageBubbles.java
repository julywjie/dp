package com.dp.agent.ui;

import com.dp.agent.chat.UiMessage;
import com.dp.agent.ui.markdown.MarkdownRenderer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 单条消息的展示节点：用户/助手气泡、执行轨迹、错误卡片。
 * 只负责"一条消息长什么样"，样式全部由 app.css 决定。
 */
final class MessageBubbles {

    private MessageBubbles() {
    }

    static Node create(UiMessage msg) {
        return create(msg, MarkdownRenderer.CONTENT_WIDTH);
    }

    static Node create(UiMessage msg, double width) {
        return create(msg, width, null);
    }

    static Node create(UiMessage msg, double width, Runnable retry) {
        return switch (msg.kind()) {
            case USER -> userRow(msg.text(), width);
            case ASSISTANT -> assistantRow(msg.text(), width, retry);
            case TRACE -> traceRow(msg.text());
            case TRACE_GROUP -> traceGroup(msg.text(), false, -1, index -> {
            });
            case ERROR -> errorRow(msg.text(), width);
        };
    }

    private static Node userRow(String text, double width) {
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(width);
        bubble.getStyleClass().add("bubble-user");
        bubble.setAccessibleText(text);

        Button copy = copyButton(text, "复制这条消息", "复制消息");
        HBox actions = actionRow(copy);
        VBox column = new VBox(2, bubble, actions);
        column.setAlignment(Pos.TOP_RIGHT);

        HBox row = new HBox(10, spacer(), column, avatarText("我", "avatar-user"));
        row.setAlignment(Pos.TOP_RIGHT);
        row.getStyleClass().add("message-row");
        wireActions(row, actions, copy);
        return row;
    }

    private static Node assistantRow(String markdown, double width, Runnable retry) {
        VBox bubble = new VBox(MarkdownRenderer.render(markdown, width));
        bubble.setMaxWidth(width + 28);
        bubble.getStyleClass().add("bubble-assistant");
        bubble.setAccessibleText(markdown);

        Button copy = copyButton(markdown, "复制这条回答", "复制回答");
        Button regenerate = retry == null ? null
                : actionButton(Icons.REFRESH, "重新生成这条回答", "重新生成", retry);
        HBox actions = regenerate == null
                ? actionRow(copy) : actionRow(copy, regenerate);
        VBox column = new VBox(2, bubble, actions);

        HBox row = new HBox(10, avatarIcon("avatar-ai"), column, spacer());
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("message-row");
        if (regenerate == null) {
            wireActions(row, actions, copy);
        } else {
            wireActions(row, actions, copy, regenerate);
        }
        return row;
    }

    /**
     * 流式输出中的助手气泡：纯文本、不解析 Markdown。
     * 每个 token 都重建整棵 Markdown 节点树是 O(n²)，长回答会明显卡顿。
     */
    static Node streamingRow(String text, double width) {
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(width);
        body.getStyleClass().add("streaming-text");

        VBox bubble = new VBox(body);
        bubble.setMaxWidth(width + 28);
        bubble.getStyleClass().addAll("bubble-assistant", "bubble-streaming");

        HBox row = new HBox(10, avatarIcon("avatar-ai"), bubble, spacer());
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("message-row");
        return row;
    }

    /** 操作条默认隐藏；悬停或键盘聚焦时出现，键盘用户同样可用。 */
    private static HBox actionRow(Button... buttons) {
        HBox actions = new HBox(2, buttons);
        actions.getStyleClass().add("msg-actions");
        actions.setOpacity(0);
        actions.setMouseTransparent(true);
        return actions;
    }

    private static void wireActions(HBox row, HBox actions, Button... buttons) {
        row.hoverProperty().addListener((obs, was, hover) -> showActions(actions, hover));
        for (Button button : buttons) {
            button.focusedProperty().addListener((obs, was, focused) ->
                    showActions(actions, focused || row.isHover()));
        }
    }

    private static void showActions(HBox actions, boolean show) {
        if (show == !actions.isMouseTransparent()) {
            return;
        }
        actions.setMouseTransparent(!show);
        FadeTransition fade = new FadeTransition(Duration.millis(120), actions);
        fade.setToValue(show ? 1 : 0);
        fade.play();
    }

    private static Button actionButton(String icon, String tooltip,
                                       String accessibleText, Runnable action) {
        Button button = new Button();
        button.setGraphic(Icons.icon(icon, 13));
        button.getStyleClass().add("msg-action");
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(accessibleText);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** 复制消息内容，点击后短暂显示对勾。 */
    private static Button copyButton(String text, String tooltip, String accessibleText) {
        Button button = new Button();
        button.setGraphic(Icons.icon(Icons.COPY, 13));
        button.getStyleClass().add("msg-action");
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(accessibleText);
        button.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            button.setGraphic(Icons.icon(Icons.CHECK, 13));
            if (!button.getStyleClass().contains("copied")) {
                button.getStyleClass().add("copied");
            }
            PauseTransition reset = new PauseTransition(Duration.seconds(1.2));
            reset.setOnFinished(ev -> {
                button.setGraphic(Icons.icon(Icons.COPY, 13));
                button.getStyleClass().remove("copied");
            });
            reset.play();
        });
        return button;
    }

    /** 轨迹文本以 emoji 开头（来自 AgentService），换成矢量图标避免字体缺字。 */
    private static final String[][] TRACE_ICONS = {
            {"🔧", Icons.WRENCH},
            {"🧠", Icons.SPARK},
            {"✅", Icons.CHECK}
    };

    private static Label traceLabel(String text) {
        String[] parts = splitTrace(text);
        Label pill = new Label(parts[1]);
        pill.setWrapText(true);
        pill.setMaxWidth(620);
        pill.getStyleClass().add("trace-pill");
        if (parts[0] != null) {
            pill.setGraphic(Icons.icon(parts[0], 12));
            pill.setContentDisplay(ContentDisplay.LEFT);
            pill.setGraphicTextGap(6);
        }
        return pill;
    }

    private static String[] splitTrace(String text) {
        if (text != null) {
            for (String[] entry : TRACE_ICONS) {
                if (text.startsWith(entry[0])) {
                    return new String[]{entry[1], text.substring(entry[0].length()).trim()};
                }
            }
        }
        return new String[]{null, text == null ? "" : text};
    }

    private static Node traceRow(String text) {
        HBox row = new HBox(traceLabel(text));
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().addAll("message-row", "message-row-center");
        return row;
    }

    /**
     * 一轮工具执行轨迹：默认折叠成一行，点击展开。
     * 展开状态由列表持有（按消息下标），重绘不会丢失。
     */
    static Node traceGroup(String text, boolean expanded, int index, IntConsumer toggle) {
        List<String> lines = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line.strip());
                }
            }
        }
        String preview = lines.isEmpty() ? "" : splitTrace(lines.get(lines.size() - 1))[1];
        if (preview.length() > 42) {
            preview = preview.substring(0, 42) + "…";
        }
        String label = "执行过程 (" + lines.size() + ")"
                + (expanded || preview.isEmpty() ? "" : " · " + preview);

        Button header = new Button(label);
        header.setGraphic(Icons.icon(expanded ? Icons.ARROW_DOWN : Icons.ARROW_FORWARD, 12));
        header.setContentDisplay(ContentDisplay.LEFT);
        header.setGraphicTextGap(6);
        header.getStyleClass().add("trace-group-header");
        header.setAccessibleText(expanded ? "收起执行过程" : "展开执行过程");
        header.setOnAction(e -> toggle.accept(index));

        VBox body = new VBox(4);
        body.getStyleClass().add("trace-group-body");
        for (String line : lines) {
            body.getChildren().add(traceLabel(line));
        }

        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setMaxHeight(200);
        scroller.getStyleClass().add("trace-group-scroll");
        scroller.setVisible(expanded);
        scroller.setManaged(expanded);

        VBox group = new VBox(4, header, scroller);
        group.setAlignment(Pos.TOP_LEFT);
        group.setMaxWidth(680);
        group.getStyleClass().add("trace-group");

        HBox row = new HBox(group);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().addAll("message-row", "message-row-center");
        return row;
    }

    private static Node errorRow(String text, double width) {
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(width);
        body.getStyleClass().add("error-text");
        HBox card = new HBox(8, Icons.icon(Icons.WARNING, 15), body);
        card.setAlignment(Pos.TOP_LEFT);
        card.setMaxWidth(width + 100);
        card.getStyleClass().add("error-card");
        HBox row = new HBox(card);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().addAll("message-row", "message-row-center");
        return row;
    }

    private static StackPane avatarText(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add("avatar-text");
        StackPane pane = new StackPane(label);
        pane.getStyleClass().addAll("avatar", styleClass);
        return pane;
    }

    private static StackPane avatarIcon(String styleClass) {
        StackPane pane = new StackPane(Icons.icon(Icons.SPARK, 15));
        pane.getStyleClass().addAll("avatar", styleClass);
        return pane;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
