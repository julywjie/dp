package com.dp.agent.ui;

import com.dp.agent.config.AppConfig;
import com.dp.agent.config.ConfigStore;
import com.dp.agent.config.ProviderConfig;
import com.dp.agent.config.ProviderType;
import com.dp.agent.config.ProviderValidator;
import com.dp.agent.config.ToolApprovalPolicy;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供商/Key 管理对话框：左侧提供商列表 + 右侧表单与全局设置卡。
 *
 * 事务语义：所有编辑都发生在 AppConfig 的深拷贝草稿上，
 * 只有「校验通过 + 落盘成功」才回写到运行配置；取消/关闭一律丢弃草稿。
 * 字段编辑与连接测试委托 {@link ProviderFormPane}。
 */
public final class SettingsDialog {

    private final AppConfig target;
    private final AppConfig draft;
    private final ConfigStore store;
    private final Window owner;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);

    private final ListView<ProviderConfig> providerList = new ListView<>();
    private final ProviderFormPane form;

    private final ComboBox<ToolApprovalPolicy> approvalBox = new ComboBox<>();
    private final ComboBox<AppConfig.ProxyMode> proxyModeBox = new ComboBox<>();
    private final TextField proxyHostField = new TextField();
    private final TextField proxyPortField = new TextField();

    private SettingsDialog(Window owner, AppConfig target, ConfigStore store) {
        this.owner = owner;
        this.target = target;
        this.store = store;
        this.draft = target.copy();
        this.form = new ProviderFormPane(store);
        // 名称/模型在表单提交后变化，列表行展示需要同步刷新。
        form.setOnCommit(providerList::refresh);
        approvalBox.getItems().setAll(ToolApprovalPolicy.values());
        approvalBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ToolApprovalPolicy policy) {
                return policy == null ? "" : policy.label();
            }

            @Override
            public ToolApprovalPolicy fromString(String text) {
                return ToolApprovalPolicy.AUTO;
            }
        });
        approvalBox.setValue(draft.getToolApprovalPolicy());
        proxyModeBox.getItems().setAll(AppConfig.ProxyMode.values());
        proxyModeBox.setValue(draft.getProxyMode());
        proxyHostField.setText(draft.getProxyHost());
        proxyPortField.setText(draft.getProxyPort() > 0
                ? String.valueOf(draft.getProxyPort()) : "");
        proxyModeBox.valueProperty().addListener((obs, oldV, newV) -> updateProxyFields());
        updateProxyFields();
    }

    public static boolean show(Window owner, AppConfig config, ConfigStore store) {
        return new SettingsDialog(owner, config, store).display();
    }

    private boolean display() {
        dialog.setTitle("模型提供商设置");
        dialog.setHeaderText("管理模型提供商与 API Key（Key 以 AES-GCM 加密存储在本机）");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(buildContent());
        dialog.getDialogPane().setPrefSize(780, 760);
        dialog.setResizable(true);
        styleDialog();
        dialog.setOnShown(e -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                AppIcon.apply(stage);
                WindowsTitleBar.apply(stage, owner);
            }
            providerList.requestFocus();
        });
        dialog.setOnHidden(e -> form.shutdown());
        form.setDialogShowing(this::isDialogShowing);

        providerList.setItems(FXCollections.observableArrayList(draft.getProviders()));
        selectInitial();
        providerList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldV, newV) -> onSelectionChanged(oldV, newV));

        // 校验/保存失败时阻止对话框关闭，用户输入不会丢。
        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!commitAndSave()) {
                event.consume();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        return result.filter(t -> t == saveType).isPresent();
    }

    /** 复用主窗口的样式表与深浅主题，避免对话框"另起一套"外观。 */
    private void styleDialog() {
        if (owner == null || owner.getScene() == null) {
            return;
        }
        dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        if (owner.getScene().getRoot().getStyleClass().contains("theme-dark")) {
            dialog.getDialogPane().getStyleClass().add("theme-dark");
        }
    }

    private Node buildContent() {
        providerList.setPrefWidth(210);
        providerList.getStyleClass().add("settings-list");
        providerList.setPlaceholder(new Label("暂无提供商，点击下方新增"));
        providerList.setCellFactory(list -> new ProviderCell());
        Button addBtn = new Button("＋ 新增");
        addBtn.getStyleClass().add("ghost-button");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> addProvider());
        Button delBtn = new Button("－ 删除");
        delBtn.getStyleClass().addAll("ghost-button", "danger-button");
        delBtn.setMaxWidth(Double.MAX_VALUE);
        delBtn.setOnAction(e -> deleteProvider());
        HBox actions = new HBox(8, addBtn, delBtn);
        HBox.setHgrow(addBtn, Priority.ALWAYS);
        HBox.setHgrow(delBtn, Priority.ALWAYS);
        VBox left = new VBox(8, providerList, actions);
        left.getStyleClass().add("settings-side");
        VBox.setVgrow(providerList, Priority.ALWAYS);

        Label safetyTitle = new Label("安全与工具");
        safetyTitle.getStyleClass().add("section-title");
        approvalBox.setMaxWidth(Double.MAX_VALUE);
        Label safetyHint = new Label("智能模式：内置安全工具直接执行，未知工具先确认；"
                + "全部确认：每次调用都弹窗。所有调用都会写入本地审计日志。");
        safetyHint.getStyleClass().add("field-label");
        safetyHint.setWrapText(true);
        VBox safetyCard = new VBox(8, safetyTitle, approvalBox, safetyHint);
        safetyCard.getStyleClass().add("settings-form-card");
        safetyCard.setMaxWidth(Double.MAX_VALUE);

        Label proxyTitle = new Label("网络代理");
        proxyTitle.getStyleClass().add("section-title");
        proxyModeBox.setMaxWidth(132);
        proxyHostField.setPromptText("代理主机");
        proxyPortField.setPromptText("端口");
        proxyPortField.setPrefWidth(82);
        HBox.setHgrow(proxyHostField, Priority.ALWAYS);
        HBox proxyRow = new HBox(8, proxyModeBox, proxyHostField, proxyPortField);
        proxyRow.setAlignment(Pos.CENTER_LEFT);
        Label proxyHint = new Label("HTTP 代理同时用于 http/https；SOCKS 代理用于全部协议。"
                + "暂不支持代理认证；保存后新请求生效，如未生效请重启应用。");
        proxyHint.getStyleClass().add("field-label");
        proxyHint.setWrapText(true);
        VBox proxyCard = new VBox(8, proxyTitle, proxyRow, proxyHint);
        proxyCard.getStyleClass().add("settings-form-card");
        proxyCard.setMaxWidth(Double.MAX_VALUE);

        VBox right = new VBox(12, form.node(), safetyCard, proxyCard);
        right.setMaxWidth(Double.MAX_VALUE);
        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        rightScroll.setPannable(true);
        rightScroll.setMinHeight(0);
        rightScroll.setPrefViewportHeight(560);
        rightScroll.getStyleClass().add("settings-scroll");
        HBox.setHgrow(rightScroll, Priority.ALWAYS);

        HBox content = new HBox(14, left, rightScroll);
        content.getStyleClass().add("settings-root");
        return content;
    }

    /** 提供商列表项：名称 + 模型/协议两行，长文本用 Tooltip 兜底。 */
    private static final class ProviderCell extends ListCell<ProviderConfig> {
        @Override
        protected void updateItem(ProviderConfig p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            Label name = new Label(p.getDisplayName());
            name.getStyleClass().add("provider-name");
            name.setMaxWidth(176);
            Label meta = new Label(p.getModelName() + " · " + p.getType().label());
            meta.getStyleClass().add("provider-meta");
            meta.setMaxWidth(176);
            setText(null);
            setGraphic(new VBox(1, name, meta));
            setTooltip(new Tooltip(p.getDisplayName() + "\n" + p.getModelName()
                    + "\n" + p.getBaseUrl()));
        }
    }

    private void selectInitial() {
        draft.selected().ifPresentOrElse(
                p -> providerList.getSelectionModel().select(p),
                () -> providerList.getSelectionModel().selectFirst());
        form.load(providerList.getSelectionModel().getSelectedItem());
    }

    private void onSelectionChanged(ProviderConfig old, ProviderConfig now) {
        form.commit(old);
        form.load(now);
    }

    private void addProvider() {
        form.commit(providerList.getSelectionModel().getSelectedItem());
        ProviderConfig p = ProviderConfig.of(UUID.randomUUID().toString(), "新提供商",
                ProviderType.OPENAI_COMPATIBLE,
                ProviderFormPane.DEFAULT_OPENAI_URL, "gpt-4o-mini");
        draft.getProviders().add(p);
        providerList.getItems().add(p);
        providerList.getSelectionModel().select(p);
    }

    private void deleteProvider() {
        ProviderConfig p = providerList.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        if (!UiDialogs.confirm(owner, "删除提供商",
                "删除「" + p.getDisplayName() + "」？",
                "该提供商的 API Key 也会一并从本机移除。")) {
            return;
        }
        draft.getProviders().remove(p);
        providerList.getItems().remove(p);
        providerList.getSelectionModel().selectFirst();
        form.load(providerList.getSelectionModel().getSelectedItem());
    }

    private boolean commitAndSave() {
        form.commit(providerList.getSelectionModel().getSelectedItem());
        draft.setToolApprovalPolicy(approvalBox.getValue());
        AppConfig.ProxyMode proxyMode = proxyModeBox.getValue() == null
                ? AppConfig.ProxyMode.NONE : proxyModeBox.getValue();
        draft.setProxyMode(proxyMode);
        draft.setProxyHost(proxyHostField.getText());
        draft.setProxyPort((int) ProviderFormPane.parse(proxyPortField.getText(), 0));

        List<String> errors = new ArrayList<>(form.validateNumbers());
        errors.addAll(validateProxy(proxyMode));
        errors.addAll(ProviderValidator.validate(draft.getProviders()));
        if (!errors.isEmpty()) {
            UiDialogs.warn(owner, "无法保存", String.join("\n", errors));
            return false;
        }
        if (draft.selected().isEmpty() && !draft.getProviders().isEmpty()) {
            draft.setSelectedProviderId(draft.getProviders().get(0).getId());
        }
        try {
            store.save(draft);
        } catch (Exception e) {
            UiDialogs.error(owner, "保存失败", e.getMessage());
            return false;
        }
        target.applyFrom(draft);
        return true;
    }

    private boolean isDialogShowing() {
        return dialog.getDialogPane().getScene() != null
                && dialog.getDialogPane().getScene().getWindow() != null
                && dialog.getDialogPane().getScene().getWindow().isShowing();
    }

    private void updateProxyFields() {
        boolean active = proxyModeBox.getValue() != null
                && proxyModeBox.getValue() != AppConfig.ProxyMode.NONE;
        proxyHostField.setDisable(!active);
        proxyPortField.setDisable(!active);
    }

    private List<String> validateProxy(AppConfig.ProxyMode mode) {
        if (mode == AppConfig.ProxyMode.NONE) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        if (proxyHostField.getText() == null || proxyHostField.getText().isBlank()) {
            errors.add("代理主机不能为空");
        }
        int port = (int) ProviderFormPane.parse(proxyPortField.getText(), 0);
        if (port < 1 || port > 65535) {
            errors.add("代理端口需在 1–65535 之间");
        }
        return errors;
    }
}
