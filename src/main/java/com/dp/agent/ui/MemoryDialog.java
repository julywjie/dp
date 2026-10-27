package com.dp.agent.ui;

import com.dp.agent.memory.MemoryEntry;
import com.dp.agent.memory.MemoryKind;
import com.dp.agent.memory.MemoryService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 记忆库管理：
 *   长期记忆（自动提取 + 手动维护，按相关度召回）
 *   通用记忆（手动维护，每次对话都注入）
 * 只做展示与编辑，持久化/召回/提取都在 memory 包。
 */
public final class MemoryDialog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Window owner;
    private final MemoryService service;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final CheckBox enabled = new CheckBox("启用记忆");
    private final CheckBox autoExtract = new CheckBox("自动提取长期记忆");
    private final Label counts = new Label();
    private final Label hint = new Label();
    private final TextField search = new TextField();
    private final ListView<MemoryEntry> longList = new ListView<>();
    private final ListView<MemoryEntry> generalList = new ListView<>();
    private final TabPane tabs = new TabPane();
    private final Tab longTab = new Tab("长期记忆");
    private final Tab generalTab = new Tab("通用记忆");
    private final TextArea editor = new TextArea();
    private final ComboBox<MemoryKind> kindBox = new ComboBox<>();
    private final CheckBox pinned = new CheckBox("置顶");
    private final Button saveBtn = new Button("添加");
    private final Button deleteBtn = new Button("删除");
    private final Button clearBtn = new Button("清空当前页");
    private boolean showingGeneral;
    private boolean loadingSelection;

    private MemoryDialog(Window owner, MemoryService service) {
        this.owner = owner;
        this.service = service;
    }

    public static void show(Window owner, MemoryService service) {
        new MemoryDialog(owner, service).display();
    }

    private void display() {
        dialog.setTitle("记忆库");
        dialog.setHeaderText("长期记忆按相关度召回；通用记忆每次对话都会注入（仅保存在本机）");
        dialog.getDialogPane().getButtonTypes().add(
                new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(buildContent());
        dialog.getDialogPane().setPrefSize(780, 640);
        dialog.setResizable(true);
        UiDialogs.applyTheme(dialog, owner);
        refresh();
        dialog.showAndWait();
    }

    private VBox buildContent() {
        enabled.setSelected(service.isEnabled());
        autoExtract.setSelected(service.isAutoExtract());
        enabled.selectedProperty().addListener((obs, oldV, value) -> {
            service.setEnabled(value);
            updateHint();
        });
        autoExtract.selectedProperty().addListener((obs, oldV, value) -> {
            service.setAutoExtract(value);
            updateHint();
        });

        HBox top = new HBox(14, enabled, autoExtract, spacer(), counts);
        top.setAlignment(Pos.CENTER_LEFT);
        hint.getStyleClass().add("field-label");
        hint.setWrapText(true);

        search.setPromptText("搜索记忆…");
        search.textProperty().addListener((obs, oldV, value) -> refresh());

        configureList(longList);
        configureList(generalList);
        longTab.setContent(longList);
        generalTab.setContent(generalList);
        longTab.setClosable(false);
        generalTab.setClosable(false);
        tabs.getTabs().setAll(longTab, generalTab);
        tabs.getStyleClass().add("memory-tabs");
        tabs.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldV, value) -> switchTab(value == generalTab));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        kindBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(MemoryKind kind) {
                return kind == null ? "" : kind.label();
            }

            @Override
            public MemoryKind fromString(String text) {
                return MemoryKind.parse(text);
            }
        });
        pinned.setVisible(false);
        pinned.setManaged(false);
        saveBtn.getStyleClass().add("primary-button");
        deleteBtn.getStyleClass().add("ghost-button");
        clearBtn.getStyleClass().addAll("ghost-button", "danger-button");
        saveBtn.setOnAction(e -> save());
        deleteBtn.setOnAction(e -> deleteSelected());
        clearBtn.setOnAction(e -> clearCurrent());
        HBox editorHead = new HBox(8, kindBox, pinned, spacer(), clearBtn, deleteBtn, saveBtn);
        editorHead.setAlignment(Pos.CENTER_LEFT);
        editor.setPromptText("输入要记住的内容…");
        editor.setPrefRowCount(2);
        editor.getStyleClass().add("memory-editor-input");
        Label editorTitle = new Label("添加 / 编辑");
        editorTitle.getStyleClass().add("section-title");
        VBox editorBox = new VBox(6, editorTitle, editorHead, editor);
        editorBox.getStyleClass().add("memory-editor");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox listBox = new VBox(8, search, tabs);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        VBox root = new VBox(10, top, hint, listBox, editorBox);
        root.setPadding(new Insets(12));
        root.getStyleClass().add("memory-root");
        updateHint();
        switchTab(false);
        return root;
    }

    private void configureList(ListView<MemoryEntry> list) {
        list.setPlaceholder(new Label("暂无记忆"));
        list.getStyleClass().add("memory-list");
        list.setCellFactory(view -> new MemoryCell());
        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldV, entry) -> loadSelection(entry));
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    private void switchTab(boolean general) {
        showingGeneral = general;
        loadingSelection = true;
        generalList.getSelectionModel().clearSelection();
        longList.getSelectionModel().clearSelection();
        kindBox.getItems().setAll(general
                ? List.of(MemoryKind.GENERAL)
                : List.of(MemoryKind.PREFERENCE, MemoryKind.FACT,
                        MemoryKind.PROJECT, MemoryKind.DECISION));
        kindBox.setValue(kindBox.getItems().get(0));
        kindBox.setDisable(general);
        pinned.setVisible(!general);
        pinned.setManaged(!general);
        pinned.setSelected(false);
        editor.clear();
        saveBtn.setText("添加");
        loadingSelection = false;
        refresh();
    }

    private void loadSelection(MemoryEntry entry) {
        if (loadingSelection) {
            return;
        }
        if (entry == null) {
            editor.clear();
            saveBtn.setText("添加");
            deleteBtn.setDisable(true);
            return;
        }
        if (entry.general() != showingGeneral) {
            return;
        }
        deleteBtn.setDisable(false);
        loadingSelection = true;
        editor.setText(entry.text());
        kindBox.setValue(entry.kind());
        pinned.setSelected(entry.pinned());
        saveBtn.setText("保存修改");
        loadingSelection = false;
    }

    private void save() {
        String text = editor.getText() == null ? "" : editor.getText().strip();
        if (text.isBlank()) {
            hint.setText("内容不能为空。");
            return;
        }
        MemoryEntry selected = currentList().getSelectionModel().getSelectedItem();
        MemoryKind kind = kindBox.getValue() == null ? MemoryKind.FACT : kindBox.getValue();
        if (selected != null) {
            service.update(selected.id(), kind, text, pinned.isSelected(), selected.importance());
        } else {
            service.addManual(kind, text, showingGeneral, showingGeneral || pinned.isSelected(), 3);
        }
        hint.setText("已保存。");
        loadingSelection = true;
        editor.clear();
        currentList().getSelectionModel().clearSelection();
        loadingSelection = false;
        saveBtn.setText("添加");
        refresh();
    }

    private void deleteSelected() {
        MemoryEntry selected = currentList().getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        service.delete(selected.id());
        refresh();
    }

    private void clearCurrent() {
        String scope = showingGeneral ? "通用记忆" : "长期记忆";
        if (UiDialogs.confirm(owner, "清空记忆", "清空全部" + scope + "？",
                "该操作不可恢复，记忆库本身仍保留。")) {
            service.clearScope(showingGeneral);
            refresh();
        }
    }

    private void refresh() {
        String query = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        List<MemoryEntry> longEntries = filter(service.entries(false), query);
        List<MemoryEntry> generalEntries = filter(service.entries(true), query);
        longList.getItems().setAll(longEntries);
        generalList.getItems().setAll(generalEntries);
        counts.setText("长期 " + service.entries(false).size()
                + " · 通用 " + service.entries(true).size());
        deleteBtn.setDisable(currentList().getSelectionModel().getSelectedItem() == null);
        clearBtn.setDisable(currentList().getItems().isEmpty());
        if (currentList().getSelectionModel().getSelectedItem() == null) {
            saveBtn.setText("添加");
        }
    }

    private static List<MemoryEntry> filter(List<MemoryEntry> entries, String query) {
        if (query == null || query.isBlank()) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> entry.text().toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    private ListView<MemoryEntry> currentList() {
        return showingGeneral ? generalList : longList;
    }

    private void updateHint() {
        if (!service.isEnabled()) {
            hint.setText("记忆已关闭：本轮对话不会注入或提取记忆。");
        } else if (service.isAutoExtract()) {
            hint.setText("记忆只保存在本机。自动提取会把本轮对话发送给当前模型；"
                    + "可用上方开关关闭。");
        } else {
            hint.setText("记忆只保存在本机。自动提取已关闭，只有手动添加的记忆会被使用。");
        }
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private final class MemoryCell extends ListCell<MemoryEntry> {
        @Override
        protected void updateItem(MemoryEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label text = new Label(entry.text());
            text.setWrapText(true);
            text.setMaxWidth(700);
            text.getStyleClass().add("memory-text");
            String source = "auto".equals(entry.source()) ? "自动" : "手动";
            String metaText = entry.kind().label() + " · " + source + " · "
                    + Instant.ofEpochMilli(entry.updatedAt()).atZone(ZoneId.systemDefault())
                    .toLocalDateTime().format(TIME)
                    + (entry.pinned() ? " · 置顶" : "");
            Label meta = new Label(metaText);
            meta.getStyleClass().add("memory-meta");
            VBox box = new VBox(2, text, meta);
            box.getStyleClass().add("memory-cell");
            setText(null);
            setGraphic(box);
        }
    }
}
