package com.dp.agent.ui;

import com.dp.agent.config.ConfigStore;
import com.dp.agent.config.ProviderConfig;
import com.dp.agent.config.ProviderType;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 提供商详情表单卡片：字段编辑、Key 状态提示与「测试连接」。
 *
 * 约定：API Key 输入框留空 = 保持已存 Key；重新输入 = 覆盖（commit 时加密落盘）。
 * 表单只与传入的 {@link ProviderConfig} 草稿对象交互，不触碰运行配置。
 */
final class ProviderFormPane {

    static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";
    static final String DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com";

    private final ConfigStore store;
    private final ConnectionTester tester = new ConnectionTester();

    private final TextField nameField = new TextField();
    private final ComboBox<ProviderType> typeBox = new ComboBox<>();
    private final TextField urlField = new TextField();
    private final TextField modelField = new TextField();
    private final TextField tempField = new TextField();
    private final TextField tokensField = new TextField();
    private final TextField timeoutField = new TextField();
    private final TextField contextWindowField = new TextField();
    private final PasswordField keyField = new PasswordField();
    private final Label keyStatus = new Label();
    private final Button testBtn = new Button("测试连接");
    private final Label testStatus = new Label();

    /** 当前回填的提供商；Key 状态与测试连接都以它为准。 */
    private ProviderConfig current;
    /** 对话框是否仍在展示；测试回调落地前由调用方注入。 */
    private BooleanSupplier dialogShowing = () -> true;
    /** commit 后回调（如刷新列表行展示）；由调用方注入。 */
    private Runnable onCommit;
    /** 表单回填时的标志，避免回填触发"覆盖已存 Key"与默认 URL 联动。 */
    private boolean loadingForm;

    ProviderFormPane(ConfigStore store) {
        this.store = store;
        typeBox.getItems().addAll(ProviderType.values());
        typeBox.setOnAction(e -> syncDefaultUrl());
        keyStatus.getStyleClass().add("field-label");
        keyField.textProperty().addListener((obs, oldV, newV) -> updateKeyStatus());
        contextWindowField.setPromptText("tokens，留空/0 使用预设默认值");
        keyField.setPromptText("留空 = 保持原 Key");
        testStatus.getStyleClass().add("field-label");
        testStatus.setWrapText(true);
        testBtn.getStyleClass().add("ghost-button");
        testBtn.setTooltip(new Tooltip("发送一条最小测试请求，会消耗少量额度"));
        testBtn.setOnAction(e -> testConnection());
    }

    void setDialogShowing(BooleanSupplier supplier) {
        this.dialogShowing = supplier;
    }

    void setOnCommit(Runnable listener) {
        this.onCommit = listener;
    }

    /** 构建表单卡片节点；可重复调用但通常只取一次。 */
    Node node() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(92);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        form.getColumnConstraints().addAll(labelColumn, fieldColumn);
        int r = 0;
        form.add(formLabel("名称"), 0, r);
        form.add(nameField, 1, r++);
        form.add(formLabel("协议"), 0, r);
        form.add(typeBox, 1, r++);
        form.add(formLabel("Base URL"), 0, r);
        form.add(urlField, 1, r++);
        form.add(formLabel("模型名"), 0, r);
        form.add(modelField, 1, r++);
        form.add(formLabel("Temperature"), 0, r);
        form.add(tempField, 1, r++);
        form.add(formLabel("最大 Tokens"), 0, r);
        form.add(tokensField, 1, r++);
        form.add(formLabel("超时(秒)"), 0, r);
        form.add(timeoutField, 1, r++);
        form.add(formLabel("上下文窗口"), 0, r);
        form.add(contextWindowField, 1, r++);
        form.add(formLabel("API Key"), 0, r);
        VBox keyBox = new VBox(3, keyField, keyStatus);
        form.add(keyBox, 1, r);

        Label title = new Label("提供商详情");
        title.getStyleClass().add("section-title");
        Label hint = new Label("提示：Key 只在创建模型实例时解密使用，不写入日志。");
        hint.getStyleClass().add("field-label");
        HBox testRow = new HBox(8, testBtn, testStatus);
        testRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(testStatus, Priority.ALWAYS);
        testStatus.setMaxWidth(Double.MAX_VALUE);
        VBox formCard = new VBox(12, title, form, hint, testRow);
        formCard.getStyleClass().add("settings-form-card");
        formCard.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(formCard, Priority.ALWAYS);
        return formCard;
    }

    /** 回填表单；清空 Key 输入框并刷新 Key 状态。 */
    void load(ProviderConfig p) {
        if (p == null) {
            return;
        }
        current = p;
        loadingForm = true;
        nameField.setText(p.getDisplayName());
        typeBox.setValue(p.getType());
        urlField.setText(p.getBaseUrl());
        modelField.setText(p.getModelName());
        tempField.setText(String.valueOf(p.getTemperature()));
        tokensField.setText(String.valueOf(p.getMaxTokens()));
        timeoutField.setText(String.valueOf(p.getTimeoutSeconds()));
        contextWindowField.setText(String.valueOf(p.effectiveContextWindow()));
        keyField.clear();
        loadingForm = false;
        updateKeyStatus();
    }

    /** 把表单当前值写回草稿；Key 框留空则不动已存密文。 */
    void commit(ProviderConfig p) {
        if (p == null) {
            return;
        }
        p.setDisplayName(nameField.getText().trim());
        p.setType(typeBox.getValue() == null ? ProviderType.OPENAI_COMPATIBLE : typeBox.getValue());
        p.setBaseUrl(urlField.getText().trim());
        p.setModelName(modelField.getText().trim());
        p.setTemperature(parse(tempField.getText(), p.getTemperature()));
        p.setMaxTokens((int) parse(tokensField.getText(), p.getMaxTokens()));
        p.setTimeoutSeconds((int) parse(timeoutField.getText(), p.getTimeoutSeconds()));
        int window = (int) parse(contextWindowField.getText(), 0);
        p.setContextWindow(window <= 0 ? 0 : Math.max(1024, window));
        String typed = keyField.getText();
        if (!typed.isBlank()) {
            p.setApiKeyEnc(store.encryptKey(typed.trim()));
        }
        if (onCommit != null) {
            onCommit.run();
        }
    }

    /** 表单文本框的原始校验：避免 "abc"/"NaN" 被静默回退成旧值。 */
    List<String> validateNumbers() {
        List<String> errors = new ArrayList<>();
        checkNumber(errors, tempField.getText(), "Temperature", true);
        checkNumber(errors, tokensField.getText(), "最大 Tokens", false);
        checkNumber(errors, timeoutField.getText(), "超时(秒)", false);
        checkNumber(errors, contextWindowField.getText(), "上下文窗口", false);
        return errors;
    }

    /** 停止后台测试线程；对话框关闭时调用。 */
    void shutdown() {
        tester.shutdownNow();
    }

    /** 用当前表单值做一次最小请求，不保存配置。 */
    private void testConnection() {
        ProviderConfig selected = current;
        if (selected == null) {
            testStatus.setText("请先选择提供商");
            return;
        }
        commit(selected);
        if (selected.getBaseUrl() == null || selected.getBaseUrl().isBlank()) {
            testStatus.setText("请先填写 Base URL");
            return;
        }
        String typed = keyField.getText().trim();
        String key = typed.isEmpty() ? store.decryptKey(selected) : typed;
        if (key.isBlank() && !ProviderResolver.isLocalEndpoint(selected.getBaseUrl())) {
            testStatus.setText("请先填写 API Key");
            return;
        }
        ProviderConfig test = selected.copy();
        test.setMaxTokens(Math.min(64, Math.max(16, test.getMaxTokens())));
        test.setTimeoutSeconds(12);

        testBtn.setDisable(true);
        testStatus.getStyleClass().removeAll("key-ok", "key-missing");
        testStatus.setText("测试中…");
        String apiKey = key.isBlank() ? "local" : key;
        tester.test(test, apiKey, (message, success) -> {
            if (!dialogShowing.getAsBoolean()) {
                return;
            }
            testStatus.setText(message);
            testStatus.getStyleClass().removeAll("key-ok", "key-missing");
            testStatus.getStyleClass().add(success ? "key-ok" : "key-missing");
            testBtn.setDisable(false);
        });
    }

    private void updateKeyStatus() {
        if (loadingForm) {
            return;
        }
        keyStatus.getStyleClass().removeAll("key-ok", "key-missing");
        String typed = keyField.getText();
        if (typed != null && !typed.isBlank()) {
            keyStatus.setText("将保存新的 Key");
            keyStatus.getStyleClass().add("key-ok");
        } else if (current != null && current.hasApiKey()) {
            keyStatus.setText("已保存（留空保持不变）");
            keyStatus.getStyleClass().add("key-ok");
        } else {
            keyStatus.setText("未配置");
            keyStatus.getStyleClass().add("key-missing");
        }
    }

    /** 协议切换时，仅在 URL 仍是默认值（或空）的前提下联动替换默认 URL。 */
    private void syncDefaultUrl() {
        ProviderType t = typeBox.getValue();
        if (t == null || loadingForm) {
            return;
        }
        String url = urlField.getText().trim();
        boolean untouched = url.isBlank()
                || url.equals(DEFAULT_OPENAI_URL) || url.equals(DEFAULT_ANTHROPIC_URL);
        if (untouched) {
            urlField.setText(t == ProviderType.ANTHROPIC ? DEFAULT_ANTHROPIC_URL : DEFAULT_OPENAI_URL);
        }
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static void checkNumber(List<String> errors, String raw, String label, boolean decimal) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value) || (!decimal && value != Math.rint(value))) {
                errors.add(label + " 不是有效数字");
            }
        } catch (NumberFormatException e) {
            errors.add(label + " 不是有效数字");
        }
    }

    /** 供对话框解析代理端口等表单数字；解析失败回退默认值。 */
    static double parse(String s, double fallback) {
        try {
            double value = Double.parseDouble(s.trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
