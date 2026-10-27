package com.dp.agent.ui;

import com.dp.agent.chat.UiMessage;
import com.dp.agent.config.ProviderConfig;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 主窗口视图：只负责布局与样式（样式集中在 resources 下的 app.css），
 * 事件通过 Callbacks 交给控制器。
 */
public class ChatView {

    /** 视图 → 控制器的事件出口。 */
    public interface Callbacks {
        void onSend(String text);

        void onRetry();

        void onStop();

        void onNewChat();

        void onSettings();

        void onProviderChanged(ProviderConfig selected);

        void onToolsToggle(boolean enabled);

        void onStreamingToggle(boolean enabled);

        void onThemeToggle(boolean dark);

        void onFormatTool();

        void onMemory();

        void onSelectSession(String id);

        void onDeleteSession(String id);
    }

    /** 外壳 / 控件 / Markdown / 设置对话框，按顺序级联。 */
    private static final String[] STYLESHEETS = {
            "/com/dp/agent/ui/app.css",
            "/com/dp/agent/ui/controls.css",
            "/com/dp/agent/ui/markdown.css",
            "/com/dp/agent/ui/settings.css",
            "/com/dp/agent/ui/format.css",
            "/com/dp/agent/ui/discover.css"
    };

    private final TopBar topBar;
    private final ChatListPanel chatListPanel =
            new ChatListPanel(this::useSuggestion, this::retryLast);
    private final TextArea input = new TextArea();
    private final VBox composer = new VBox();
    private final Button sendBtn = new Button("发送");
    private final Button stopBtn = new Button("停止");
    private final SessionSidebar sessionSidebar = new SessionSidebar();
    private final Button formatBtn = new Button();
    private final Label statusLabel = new Label("就绪");
    private final Circle statusDot = new Circle(3.5);
    /** 忙碌时状态点的呼吸动画。 */
    private final Timeline busyPulse = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(statusDot.opacityProperty(), 1.0)),
            new KeyFrame(Duration.seconds(0.7), new KeyValue(statusDot.opacityProperty(), 0.3)));

    private boolean darkTheme;
    private boolean busy;
    private BorderPane root;
    /** 真正的场景根：主布局 + 顶部 Toast 覆盖层。 */
    private StackPane layer;
    private Stage stage;
    private final Toast toast = new Toast();
    private final MarketTicker marketTicker = new MarketTicker();
    private Node discoverNode;
    private StackPane chatArea;
    private VBox bottomArea;
    private boolean discoverOnBuild;
    private final List<String> stylesheets = new ArrayList<>();

    private final Callbacks callbacks;

    public ChatView(Callbacks callbacks) {
        this.callbacks = callbacks;
        this.topBar = new TopBar(new TopBar.Actions() {
            @Override
            public void onToolsToggle(boolean enabled) {
                callbacks.onToolsToggle(enabled);
            }

            @Override
            public void onStreamingToggle(boolean enabled) {
                callbacks.onStreamingToggle(enabled);
            }

            @Override
            public void onThemeToggle() {
                toggleTheme();
            }

            @Override
            public void onSettings() {
                callbacks.onSettings();
            }

            @Override
            public void onMemory() {
                callbacks.onMemory();
            }

            @Override
            public void syncTheme() {
                ThemeManager.syncToWindows(darkTheme, stylesheets);
            }
        });
        configureControls();
    }

    private void configureControls() {
        formatBtn.setGraphic(Icons.icon(Icons.CODE, 16));
        formatBtn.getStyleClass().add("icon-button");
        formatBtn.setTooltip(new Tooltip("数据格式化工具（JSON / XML）"));
        formatBtn.setAccessibleText("数据格式化工具");
        formatBtn.setOnAction(e -> callbacks.onFormatTool());

        statusDot.getStyleClass().add("status-dot");
        statusLabel.getStyleClass().add("status-label");
        busyPulse.setAutoReverse(true);
        busyPulse.setCycleCount(Animation.INDEFINITE);
        stopBtn.setGraphic(Icons.icon(Icons.STOP, 11));
        stopBtn.setGraphicTextGap(6);
        stopBtn.setDisable(true);
    }

    public Scene buildScene() {
        root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(topBar.build());
        chatArea = buildChatArea();
        bottomArea = buildComposer();
        root.setCenter(chatArea);
        root.setBottom(bottomArea);
        root.setLeft(sessionSidebar.node());

        layer = new StackPane(root, marketTicker.node(), toast.node());
        StackPane.setAlignment(marketTicker.node(), Pos.BOTTOM_RIGHT);
        StackPane.setMargin(marketTicker.node(), new Insets(0, 16, 14, 0));
        marketTicker.node().heightProperty().addListener((obs, oldH, newH) -> updateMarketLayout());
        bottomArea.heightProperty().addListener((obs, oldH, newH) -> updateMarketLayout());
        Scene scene = new Scene(layer, 1180, 760);
        for (String path : STYLESHEETS) {
            var css = ChatView.class.getResource(path);
            if (css != null) {
                stylesheets.add(css.toExternalForm());
                scene.getStylesheets().add(css.toExternalForm());
            }
        }
        installShortcuts(scene);
        applyTheme();
        if (discoverOnBuild) {
            showDiscover();
        }
        return scene;
    }

    /** 全局快捷键：Esc 停止、Ctrl+N 新会话、Ctrl+, 设置。 */
    private void installShortcuts(Scene scene) {
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN),
                callbacks::onNewChat);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                callbacks::onSettings);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN),
                callbacks::onMemory);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                () -> {
                    if (busy) {
                        callbacks.onStop();
                    }
                });
    }

    /** 由控制器注入发现页节点；实际切换在 showDiscover/showChat。 */
    public void installDiscover(Node node) {
        this.discoverNode = node;
    }

    /** 切换到 AI 热榜：隐藏输入区，保留顶栏与侧栏。 */
    public void showDiscover() {
        if (root == null) {
            discoverOnBuild = true;
            return;
        }
        if (discoverNode == null) {
            return;
        }
        discoverOnBuild = false;
        root.setCenter(discoverNode);
        root.setBottom(null);
        updateMarketLayout();
    }

    public void showChat() {
        discoverOnBuild = false;
        if (root == null) {
            return;
        }
        root.setCenter(chatArea);
        root.setBottom(bottomArea);
        input.requestFocus();
        updateMarketLayout();
    }

    /**
     * 行情卡固定在窗口右下角：聊天页贴在输入框上方，发现页贴窗口底部。
     * “回到底部”按钮需要上移，避免被行情卡遮住。
     */
    private void updateMarketLayout() {
        if (layer == null || root == null) {
            return;
        }
        boolean chatVisible = root.getBottom() == bottomArea;
        double bottom = chatVisible ? bottomArea.getHeight() + 10 : 14;
        StackPane.setMargin(marketTicker.node(), new Insets(0, 16, bottom, 0));
        double scrollBottom = chatVisible
                ? marketTicker.node().getHeight() + 24 : 12;
        StackPane.setMargin(chatListPanel.scrollButton(),
                new Insets(0, 18, scrollBottom, 0));
    }

    public void close() {
        marketTicker.close();
    }

    private StackPane buildChatArea() {
        return chatListPanel.area();
    }

    private VBox buildComposer() {
        sendBtn.setGraphic(Icons.icon(Icons.SEND, 14));
        sendBtn.setGraphicTextGap(6);
        sendBtn.getStyleClass().add("primary-button");
        sendBtn.setOnAction(e -> fireSend());
        stopBtn.getStyleClass().addAll("ghost-button", "stop-button");
        stopBtn.setOnAction(e -> callbacks.onStop());

        input.setPromptText("输入消息，Enter 发送，Shift+Enter 换行");
        input.setPrefRowCount(3);
        input.getStyleClass().add("composer-input");
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                fireSend();
            }
        });
        input.focusedProperty().addListener((obs, oldV, focused) -> {
            if (focused) {
                composer.getStyleClass().add("focused");
            } else {
                composer.getStyleClass().remove("focused");
            }
        });

        HBox actions = new HBox(8, statusDot, statusLabel, spacer(), formatBtn, stopBtn, sendBtn);
        actions.getStyleClass().add("composer-actions");
        actions.setAlignment(Pos.CENTER_LEFT);
        composer.getChildren().setAll(input, actions);
        composer.getStyleClass().add("composer");

        Label hint = new Label("Enter 发送 · Shift+Enter 换行 · Esc 停止 · "
                + "Ctrl+N 新会话 · Ctrl+, 设置");
        hint.getStyleClass().add("hint-label");
        VBox bottom = new VBox(6, composer, hint);
        bottom.getStyleClass().add("bottom-area");
        return bottom;
    }

    private void retryLast() {
        if (callbacks != null) {
            callbacks.onRetry();
        }
    }

    /** 空状态卡片：把提示词填入输入框，光标置尾，焦点交给输入框。 */
    private void useSuggestion(String prompt) {
        input.setText(prompt);
        input.requestFocus();
        input.positionCaret(prompt.length());
    }

    private void fireSend() {
        String text = input.getText().trim();
        if (!text.isEmpty()) {
            callbacks.onSend(text);
        }
    }

    // ---- 主题 ----

    public void setDarkTheme(boolean dark) {
        darkTheme = dark;
        applyTheme();
    }

    private void toggleTheme() {
        darkTheme = !darkTheme;
        applyTheme();
        callbacks.onThemeToggle(darkTheme);
    }

    /** 主题通过 root 上的 style class 切换，颜色全部来自 app.css 的 looked-up color。 */
    private void applyTheme() {
        ThemeManager.apply(layer == null ? root : layer, topBar.themeButton(), darkTheme);
        ThemeManager.syncToWindows(darkTheme, stylesheets);
        applyTitleBar();
    }

    /** 系统标题栏/窗口边框与深色主题同步，消除顶部白边。 */
    private void applyTitleBar() {
        if (stage != null && stage.isShowing()) {
            WindowsTitleBar.apply(stage, darkTheme);
        }
    }

    // ---- 控制器调用的状态更新接口 ----

    public ComboBox<ProviderConfig> providerBox() {
        return topBar.providerBox();
    }

    public SessionSidebar sessionSidebar() {
        return sessionSidebar;
    }

    public boolean isToolsEnabled() {
        return topBar.isToolsEnabled();
    }

    public void setToolsEnabled(boolean enabled) {
        topBar.setToolsEnabled(enabled);
    }

    public boolean isStreamingEnabled() {
        return topBar.isStreamingEnabled();
    }

    public void setStreamingEnabled(boolean enabled) {
        topBar.setStreamingEnabled(enabled);
    }

    public void appendToken(String chunk) {
        chatListPanel.appendToken(chunk);
    }

    public void finishStreaming(String finalText) {
        chatListPanel.finishStreaming(finalText);
    }

    /** 异常/取消：保留已生成的部分气泡，只解除后续 token 的归属。 */
    public void abortStreaming() {
        chatListPanel.abortStreaming();
    }

    public void appendMessage(UiMessage msg) {
        chatListPanel.appendMessage(msg);
    }

    /** @param scroll 是否滚到底部；用户正在回看历史时不打断。 */
    public void appendMessage(UiMessage msg, boolean scroll) {
        chatListPanel.appendMessage(msg, scroll);
    }

    /** 一轮中的多条工具轨迹合并到同一个可折叠分组。 */
    public void appendTrace(String event) {
        chatListPanel.appendTrace(event);
    }

    public void clearChat() {
        chatListPanel.clear();
    }

    /** 恢复历史会话：整批替换，且不播放入场动效。 */
    public void setMessages(List<UiMessage> messages) {
        chatListPanel.setMessages(messages);
    }

    /** 全局提示：配置/保存错误、模型切换等，发现页也能看到。 */
    public void showToast(String message, boolean error) {
        toast.show(message, error);
    }

    public void clearInput() {
        input.clear();
    }

    public void setBusy(boolean busy, String status) {
        this.busy = busy;
        sendBtn.setDisable(busy);
        stopBtn.setDisable(!busy);
        topBar.setBusy(busy);
        // 忙碌时输入框仍可编辑：用户可以先把下一句话打好，只禁用发送。
        input.setDisable(false);
        statusLabel.setText(status);
        if (busy) {
            if (!statusLabel.getStyleClass().contains("busy")) {
                statusLabel.getStyleClass().add("busy");
                statusDot.getStyleClass().add("busy");
            }
            if (busyPulse.getStatus() != Animation.Status.RUNNING) {
                busyPulse.playFromStart();
            }
        } else {
            statusLabel.getStyleClass().remove("busy");
            statusDot.getStyleClass().remove("busy");
            busyPulse.stop();
            statusDot.setOpacity(1);
        }
        if (!busy) {
            input.requestFocus();
        }
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public void attachStage(Stage stage) {
        this.stage = stage;
        stage.setTitle("DP Agent — LangGraph 桌面智能助手");
        stage.setMinWidth(940);
        stage.setMinHeight(580);
        stage.setOnShown(e -> {
            applyTitleBar();
            input.requestFocus();
        });
    }
}
