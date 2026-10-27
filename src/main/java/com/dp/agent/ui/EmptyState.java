package com.dp.agent.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.util.function.Consumer;

/**
 * 空会话引导页：品牌标识 + 说明 + 快捷提问卡片。
 * 卡片点击后把提示词交给回调（由 ChatView 填入输入框），不直接触碰业务。
 */
final class EmptyState {

    private static final String[][] SUGGESTIONS = {
            {Icons.CODE, "解释代码", "贴一段代码，逐行讲清楚",
                    "请逐行解释下面这段代码的作用，并指出可能的坑：\n"},
            {Icons.EDIT, "写周报", "把本周工作整理成结构化周报",
                    "帮我根据以下工作内容写一份本周周报，分「进展 / 问题 / 下周计划」：\n"},
            {Icons.TRANSLATE, "翻译润色", "中英互译并优化表达",
                    "把下面这段内容翻译成中文，并润色得更自然、简洁：\n"},
            {Icons.WRENCH, "算一算", "调用工具做计算或查询时间",
                    "用工具计算 (1234 * 56 - 789) / 3 的结果，并说明计算过程。"}
    };

    private final Consumer<String> onPick;

    EmptyState(Consumer<String> onPick) {
        this.onPick = onPick;
    }

    VBox build() {
        StackPane orb = new StackPane(Icons.icon(Icons.SPARK, 26));
        orb.getStyleClass().add("empty-orb");

        Label title = new Label("开始新的对话");
        title.getStyleClass().add("empty-title");

        Label subtitle = new Label("先在 ⚙ 设置 中配置模型与 API Key，然后在这里提问");
        subtitle.getStyleClass().add("empty-subtitle");
        subtitle.setTextAlignment(TextAlignment.CENTER);

        VBox box = new VBox(10, orb, title, subtitle, grid());
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(610);
        return box;
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("suggest-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setMaxWidth(560);
        grid.setAlignment(Pos.CENTER);
        VBox.setMargin(grid, new Insets(18, 0, 0, 0));

        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(50);
        left.setHgrow(Priority.ALWAYS);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(50);
        right.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(left, right);

        for (int i = 0; i < SUGGESTIONS.length; i++) {
            String[] s = SUGGESTIONS[i];
            grid.add(card(s[0], s[1], s[2], s[3]), i % 2, i / 2);
        }
        return grid;
    }

    private Button card(String iconPath, String title, String subtitle, String prompt) {
        Node icon = Icons.icon(iconPath, 15);
        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().add("suggest-icon");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("suggest-title");
        Label subLabel = new Label(subtitle);
        subLabel.getStyleClass().add("suggest-sub");
        subLabel.setWrapText(true);

        VBox text = new VBox(1, titleLabel, subLabel);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox content = new HBox(10, iconBox, text);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(230);

        Button button = new Button();
        button.setGraphic(content);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setPrefHeight(62);
        button.getStyleClass().add("suggest-card");
        button.setOnAction(e -> onPick.accept(prompt));
        GridPane.setHgrow(button, Priority.ALWAYS);
        return button;
    }
}
