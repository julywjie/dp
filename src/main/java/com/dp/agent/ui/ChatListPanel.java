package com.dp.agent.ui;

import com.dp.agent.chat.UiMessage;
import javafx.animation.PauseTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 消息列表：负责单元格构建、流式原位更新、轨迹分组、自动滚底与响应式宽度。
 * 从 ChatView 拆出，避免视图类继续膨胀，也让这些状态机集中在一处。
 */
final class ChatListPanel {

    private final ListView<UiMessage> list = new ListView<>();
    private final ScrollToBottom scrollToBottom;
    private final Runnable retryHandler;
    private final Set<UiMessage> animated =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final DoubleProperty contentWidth = new SimpleDoubleProperty(560);
    private final PauseTransition resizeDebounce =
            new PauseTransition(Duration.millis(160));

    private int streamingIndex = -1;
    private int skipAnimIndex = -1;
    private int traceIndex = -1;
    private int expandedTraceIndex = -1;

    ChatListPanel(Consumer<String> suggestionHandler, Runnable retryHandler) {
        this.retryHandler = retryHandler;
        scrollToBottom = new ScrollToBottom(list);
        list.setCellFactory(view -> new MessageCell());
        list.setItems(FXCollections.observableArrayList());
        list.getStyleClass().add("chat-list");
        list.setPlaceholder(new EmptyState(suggestionHandler).build());
        list.setFocusTraversable(true);
        list.widthProperty().addListener((obs, oldW, newW) -> updateContentWidth());
        resizeDebounce.setOnFinished(e -> list.refresh());
        updateContentWidth();
    }

    StackPane area() {
        StackPane area = new StackPane(list, scrollToBottom.node());
        area.getStyleClass().add("chat-area");
        StackPane.setAlignment(scrollToBottom.node(), Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollToBottom.node(), new Insets(0, 18, 12, 0));
        return area;
    }

    Node scrollButton() {
        return scrollToBottom.node();
    }

    private void updateContentWidth() {
        double listWidth = list.getWidth();
        double target = listWidth <= 0
                ? 560
                : Math.max(480, Math.min(760, listWidth * 0.66 - 70));
        if (Math.abs(target - contentWidth.get()) < 1) {
            return;
        }
        contentWidth.set(target);
        resizeDebounce.playFromStart();
    }

    void appendToken(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        var items = list.getItems();
        if (streamingIndex < 0 || streamingIndex >= items.size()) {
            streamingIndex = items.size();
            appendMessage(UiMessage.assistant(chunk), true);
        } else {
            boolean follow = scrollToBottom.isFollowing();
            items.set(streamingIndex,
                    UiMessage.assistant(items.get(streamingIndex).text() + chunk));
            if (follow) {
                list.scrollTo(streamingIndex);
            }
        }
    }

    void finishStreaming(String finalText) {
        int index = streamingIndex;
        streamingIndex = -1;
        if (finalText == null || finalText.isBlank()) {
            return;
        }
        boolean follow = scrollToBottom.isFollowing();
        if (index >= 0 && index < list.getItems().size()) {
            skipAnimIndex = index;
            list.getItems().set(index, UiMessage.assistant(finalText));
            if (follow) {
                list.scrollTo(index);
            }
        } else {
            appendMessage(UiMessage.assistant(finalText), follow);
        }
    }

    void abortStreaming() {
        streamingIndex = -1;
    }

    void appendMessage(UiMessage msg) {
        appendMessage(msg, scrollToBottom.isFollowing());
    }

    void appendMessage(UiMessage msg, boolean scroll) {
        if (msg.kind() == UiMessage.Kind.USER) {
            traceIndex = -1; // 新一轮开始，下一条轨迹创建新分组
        }
        list.getItems().add(msg);
        if (scroll) {
            list.scrollTo(list.getItems().size() - 1);
        }
    }

    /** 同一轮的工具轨迹合并进一个可折叠分组。 */
    void appendTrace(String event) {
        if (event == null || event.isBlank()) {
            return;
        }
        var items = list.getItems();
        boolean follow = scrollToBottom.isFollowing();
        if (traceIndex >= 0 && traceIndex < items.size()
                && items.get(traceIndex).kind() == UiMessage.Kind.TRACE_GROUP) {
            UiMessage old = items.get(traceIndex);
            skipAnimIndex = traceIndex;
            items.set(traceIndex,
                    UiMessage.traceGroup(old.text() + "\n" + event));
            if (follow) {
                list.scrollTo(traceIndex);
            }
        } else {
            traceIndex = items.size();
            appendMessage(UiMessage.traceGroup(event), follow);
        }
    }

    void clear() {
        list.getItems().clear();
        streamingIndex = -1;
        skipAnimIndex = -1;
        traceIndex = -1;
        expandedTraceIndex = -1;
        animated.clear();
        scrollToBottom.reset();
    }

    void setMessages(List<UiMessage> messages) {
        streamingIndex = -1;
        skipAnimIndex = -1;
        traceIndex = -1;
        expandedTraceIndex = -1;
        animated.clear();
        animated.addAll(messages);
        list.getItems().setAll(messages);
        scrollToBottom.reset();
        if (!messages.isEmpty()) {
            list.scrollTo(messages.size() - 1);
        }
    }

    private void toggleTrace(int index) {
        expandedTraceIndex = expandedTraceIndex == index ? -1 : index;
        list.refresh();
    }

    private boolean isLastAssistant(int index) {
        var items = list.getItems();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).kind() == UiMessage.Kind.ASSISTANT) {
                return i == index;
            }
        }
        return false;
    }

    private boolean shouldAnimate(UiMessage msg, int index) {
        if (msg.kind() == UiMessage.Kind.ASSISTANT
                && streamingIndex >= 0 && index == streamingIndex) {
            return false;
        }
        if (index == skipAnimIndex) {
            animated.add(msg);
            skipAnimIndex = -1;
            return false;
        }
        return animated.add(msg);
    }

    /** 单元格：流式气泡用纯文本，轨迹分组用可折叠节点，其余走 MessageBubbles。 */
    private final class MessageCell extends ListCell<UiMessage> {
        @Override
        protected void updateItem(UiMessage msg, boolean empty) {
            super.updateItem(msg, empty);
            if (empty || msg == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            Node node;
            if (msg.kind() == UiMessage.Kind.TRACE_GROUP) {
                node = MessageBubbles.traceGroup(msg.text(),
                        getIndex() == expandedTraceIndex, getIndex(),
                        ChatListPanel.this::toggleTrace);
            } else if (msg.kind() == UiMessage.Kind.ASSISTANT
                    && getIndex() == streamingIndex) {
                node = MessageBubbles.streamingRow(msg.text(), contentWidth.get());
            } else {
                Runnable retry = msg.kind() == UiMessage.Kind.ASSISTANT
                        && isLastAssistant(getIndex()) ? retryHandler : null;
                node = MessageBubbles.create(msg, contentWidth.get(), retry);
            }
            if (shouldAnimate(msg, getIndex())) {
                Motion.enter(node);
            }
            setGraphic(node);
        }
    }
}
