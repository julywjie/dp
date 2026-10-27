package com.dp.agent.ui;

import com.dp.agent.session.SessionMeta;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 左侧历史会话栏：新建、切换、删除。
 * 只负责展示与事件转发，数据由 SessionManager / ChatController 提供。
 */
public final class SessionSidebar {

    public interface Callbacks {
        void onSelect(String id);

        void onDelete(String id);

        void onNew();

        void onDiscover();
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final VBox root = new VBox();
    private final ListView<Object> list = new ListView<>();
    private final TextField search = new TextField();
    private final Label historyTitle = new Label("历史会话");
    private Callbacks callbacks;
    private List<SessionMeta> sessions = List.of();
    private String selectedId = "";
    private boolean syncing;

    public SessionSidebar() {
        Label title = new Label("导航");
        title.getStyleClass().add("sidebar-section");

        Button discover = new Button("AI 热榜");
        discover.setGraphic(Icons.icon(Icons.SPARK, 13));
        discover.setContentDisplay(ContentDisplay.LEFT);
        discover.setGraphicTextGap(6);
        discover.setMaxWidth(Double.MAX_VALUE);
        discover.getStyleClass().add("sidebar-discover");
        discover.setOnAction(e -> {
            if (callbacks != null) {
                callbacks.onDiscover();
            }
        });

        Button newChat = new Button("新会话");
        newChat.setGraphic(Icons.icon(Icons.PLUS, 13));
        newChat.setContentDisplay(ContentDisplay.LEFT);
        newChat.setGraphicTextGap(6);
        newChat.setMaxWidth(Double.MAX_VALUE);
        newChat.getStyleClass().add("sidebar-new");
        newChat.setOnAction(e -> {
            if (callbacks != null) {
                callbacks.onNew();
            }
        });

        historyTitle.getStyleClass().add("sidebar-section");
        Region divider = new Region();
        divider.getStyleClass().add("sidebar-divider");

        search.setPromptText("搜索会话");
        search.getStyleClass().add("sidebar-search");
        search.textProperty().addListener((obs, old, value) -> rebuildRows());
        VBox.setMargin(search, new Insets(0, 4, 2, 4));

        list.getStyleClass().add("session-list");
        list.setPlaceholder(emptyPlaceholder(false));
        list.setCellFactory(view -> new SessionCell());
        list.setFocusTraversable(true);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (!syncing && selected instanceof SessionMeta meta && callbacks != null) {
                callbacks.onSelect(meta.id());
            }
        });

        Label footer = new Label("会话只保存在本机");
        footer.getStyleClass().add("sidebar-footer");

        root.getChildren().addAll(title, discover, newChat, divider,
                historyTitle, search, list, footer);
        VBox.setMargin(divider, new Insets(4, 2, 2, 2));
        root.getStyleClass().add("session-sidebar");
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    public Node node() {
        return root;
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setSessions(List<SessionMeta> sessions, String selectedId) {
        this.sessions = List.copyOf(sessions);
        this.selectedId = selectedId == null ? "" : selectedId;
        rebuildRows();
    }

    private void rebuildRows() {
        String query = search.getText() == null
                ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<SessionMeta> visible = sessions.stream()
                .filter(meta -> query.isEmpty()
                        || (meta.title() != null
                        && meta.title().toLowerCase(Locale.ROOT).contains(query)))
                .toList();
        syncing = true;
        list.setPlaceholder(emptyPlaceholder(!query.isEmpty()));
        historyTitle.setText("历史会话 · " + visible.size()
                + (query.isEmpty() ? "" : " / " + sessions.size()));
        List<Object> rows = new ArrayList<>();
        String group = null;
        for (SessionMeta meta : visible) {
            String current = groupOf(meta.updatedAt());
            if (!current.equals(group)) {
                group = current;
                rows.add(new SessionGroup(current));
            }
            rows.add(meta);
        }
        list.getItems().setAll(rows);
        boolean selectedVisible = false;
        for (Object row : rows) {
            if (row instanceof SessionMeta meta && meta.id().equals(selectedId)) {
                list.getSelectionModel().select(meta);
                list.scrollTo(meta);
                selectedVisible = true;
                break;
            }
        }
        if (!selectedVisible) {
            list.getSelectionModel().clearSelection();
        }
        syncing = false;
    }

    private Label emptyPlaceholder(boolean filtered) {
        Label label = new Label(filtered ? "没有匹配的会话" : "暂无历史会话");
        label.getStyleClass().add("sidebar-empty");
        return label;
    }

    private static String groupOf(long epochMillis) {
        LocalDate day = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return "今天";
        }
        if (day.equals(today.minusDays(1))) {
            return "昨天";
        }
        return "更早";
    }

    private record SessionGroup(String label) {
    }

    private final class SessionCell extends ListCell<Object> {
        private final Label title = new Label();
        private final Label time = new Label();
        private final Button delete = new Button();
        private final HBox content;

        SessionCell() {
            title.getStyleClass().add("session-title");
            title.setMaxWidth(138);
            time.getStyleClass().add("session-time");

            delete.setGraphic(Icons.icon(Icons.TRASH, 13));
            delete.getStyleClass().add("session-delete");
            delete.setTooltip(new Tooltip("删除会话"));
            delete.setAccessibleText("删除会话");
            delete.setOnAction(e -> {
                if (getItem() instanceof SessionMeta meta && callbacks != null) {
                    callbacks.onDelete(meta.id());
                }
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            VBox text = new VBox(2, title, time);
            content = new HBox(6, text, spacer, delete);
            content.setAlignment(Pos.CENTER_LEFT);
            content.getStyleClass().add("session-cell");
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof SessionGroup group) {
                Label label = new Label(group.label());
                label.getStyleClass().add("session-group");
                setText(null);
                setGraphic(label);
                return;
            }
            if (!(item instanceof SessionMeta meta)) {
                setText(null);
                setGraphic(null);
                return;
            }
            title.setText(meta.title());
            time.setText(relativeTime(meta.updatedAt()));
            setText(null);
            setGraphic(content);
        }

        private String relativeTime(long epochMillis) {
            ZonedDateTime moment = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
            LocalDate today = LocalDate.now();
            LocalDate day = moment.toLocalDate();
            if (day.equals(today) || day.equals(today.minusDays(1))) {
                return moment.format(TIME); // 分组标题已表达“今天/昨天”
            }
            return day.getYear() == today.getYear()
                    ? moment.format(MONTH_DAY) : moment.format(FULL_DATE);
        }
    }
}
