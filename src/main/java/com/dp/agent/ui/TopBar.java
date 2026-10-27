package com.dp.agent.ui;

import com.dp.agent.config.ProviderConfig;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** 顶栏：品牌、模型选择、工具/流式开关、主题与设置入口。 */
final class TopBar {

    interface Actions {
        void onToolsToggle(boolean enabled);

        void onStreamingToggle(boolean enabled);

        void onThemeToggle();

        void onSettings();

        void onMemory();

        void syncTheme();
    }

    private final Actions actions;
    private final ComboBox<ProviderConfig> providerBox = new ComboBox<>();
    private final CheckBox toolsBox = new CheckBox("启用工具");
    private final CheckBox streamingBox = new CheckBox("流式输出");
    private final Button memoryBtn = new Button();
    private final Button themeBtn = new Button();
    private final Button settingsBtn = new Button();
    private final Button moreBtn = new Button();
    private boolean syncing;

    TopBar(Actions actions) {
        this.actions = actions;
        configure();
    }

    HBox build() {
        StackPane mark = new StackPane(Icons.icon(Icons.SPARK, 18));
        mark.getStyleClass().add("brand-mark");
        Label title = new Label("DP Agent");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("LangGraph 智能助手");
        subtitle.getStyleClass().add("brand-subtitle");
        VBox brandText = new VBox(1, title, subtitle);
        brandText.setAlignment(Pos.CENTER_LEFT);
        HBox brand = new HBox(9, mark, brandText);
        brand.setAlignment(Pos.CENTER_LEFT);

        HBox actions = new HBox(6, providerBox, separator(),
                moreBtn, memoryBtn, themeBtn, settingsBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox bar = new HBox(12, brand, spacer(), actions);
        bar.getStyleClass().add("top-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Region separator() {
        Region divider = new Region();
        divider.getStyleClass().add("top-separator");
        return divider;
    }

    ComboBox<ProviderConfig> providerBox() {
        return providerBox;
    }

    Button themeButton() {
        return themeBtn;
    }

    boolean isToolsEnabled() {
        return toolsBox.isSelected();
    }

    void setToolsEnabled(boolean enabled) {
        syncing = true;
        toolsBox.setSelected(enabled);
        syncing = false;
    }

    boolean isStreamingEnabled() {
        return streamingBox.isSelected();
    }

    void setStreamingEnabled(boolean enabled) {
        syncing = true;
        streamingBox.setSelected(enabled);
        syncing = false;
    }

    void setBusy(boolean busy) {
        providerBox.setDisable(busy);
        settingsBtn.setDisable(busy);
    }

    private void configure() {
        providerBox.setPrefWidth(250);
        providerBox.setMinWidth(140);
        providerBox.setPromptText("选择模型");
        providerBox.getStyleClass().add("model-combo");
        providerBox.showingProperty().addListener((obs, oldV, showing) -> {
            if (showing) {
                Platform.runLater(actions::syncTheme);
            }
        });
        providerBox.valueProperty().addListener((obs, oldV, selected) -> providerBox.setTooltip(
                selected == null ? null
                        : new Tooltip(selected.getDisplayName() + " · " + selected.getModelName())));

        moreBtn.setGraphic(Icons.icon(Icons.MORE, 17));
        moreBtn.getStyleClass().add("icon-button");
        moreBtn.setTooltip(new Tooltip("更多选项（工具 / 流式）"));
        moreBtn.setAccessibleText("更多选项");

        toolsBox.getStyleClass().add("menu-check");
        toolsBox.setTooltip(new Tooltip("允许模型调用本地工具（计算器 / 时间等）"));
        streamingBox.getStyleClass().add("menu-check");
        streamingBox.setTooltip(new Tooltip("开启后按 token 实时输出，关闭则按步骤整段输出"));
        CustomMenuItem toolsItem = new CustomMenuItem(toolsBox, false);
        CustomMenuItem streamingItem = new CustomMenuItem(streamingBox, false);
        ContextMenu moreMenu = new ContextMenu(toolsItem, streamingItem);
        moreMenu.getStyleClass().add("app-menu");
        toolsBox.selectedProperty().addListener((obs, old, selected) -> {
            if (!syncing) {
                actions.onToolsToggle(selected);
            }
        });
        streamingBox.selectedProperty().addListener((obs, old, selected) -> {
            if (!syncing) {
                actions.onStreamingToggle(selected);
            }
        });
        moreBtn.setOnAction(e -> {
            moreMenu.getStyleClass().removeAll("root", "theme-dark");
            moreMenu.getStyleClass().add("root");
            if (moreBtn.getScene() != null
                    && moreBtn.getScene().getRoot().getStyleClass().contains("theme-dark")) {
                moreMenu.getStyleClass().add("theme-dark");
            }
            moreMenu.show(moreBtn, Side.BOTTOM, 0, 5);
        });

        memoryBtn.setGraphic(Icons.icon(Icons.BOOKMARK, 16));
        memoryBtn.getStyleClass().add("icon-button");
        memoryBtn.setTooltip(new Tooltip("长期记忆 / 通用记忆（Ctrl+M）"));
        memoryBtn.setAccessibleText("打开记忆库");
        memoryBtn.setOnAction(e -> actions.onMemory());

        themeBtn.getStyleClass().add("icon-button");
        themeBtn.setTooltip(new Tooltip("切换深色 / 浅色主题"));
        themeBtn.setAccessibleText("切换深色 / 浅色主题");
        themeBtn.setOnAction(e -> actions.onThemeToggle());

        settingsBtn.setGraphic(Icons.icon(Icons.GEAR, 17));
        settingsBtn.getStyleClass().add("icon-button");
        settingsBtn.setTooltip(new Tooltip("模型提供商与 API Key 设置"));
        settingsBtn.setAccessibleText("打开设置");
        settingsBtn.setOnAction(e -> actions.onSettings());
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
