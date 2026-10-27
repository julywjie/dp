package com.dp.agent.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ScrollEvent;

/**
 * "回到底部"悬浮按钮：仅在用户向上滚动后出现，点击回到最新消息。
 * 不依赖不同内容高度下不一致的 scrollbar 语义（短列表 max=条目数，长列表 max=1）。
 */
final class ScrollToBottom {

    private final ListView<?> list;
    private final Button button = new Button();
    private ScrollBar verticalBar;
    private boolean scrolledUp;

    ScrollToBottom(ListView<?> list) {
        this.list = list;
        button.setGraphic(Icons.icon(Icons.ARROW_DOWN, 16));
        button.getStyleClass().add("scroll-bottom");
        button.setTooltip(new Tooltip("回到最新消息"));
        button.setVisible(false);
        button.setOnAction(e -> {
            list.scrollTo(list.getItems().size() - 1);
            scrolledUp = false;
            update();
        });

        list.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() > 0 && !list.getItems().isEmpty()) {
                scrolledUp = true;
                update();
            }
        });
        list.getItems().addListener((ListChangeListener<Object>) change -> {
            if (list.getItems().isEmpty()) {
                scrolledUp = false;
                update();
            }
        });
        list.skinProperty().addListener((obs, oldSkin, skin) -> {
            if (skin != null) {
                Platform.runLater(this::attach);
            }
        });
        if (list.getSkin() != null) {
            Platform.runLater(this::attach);
        }
    }

    Node node() {
        return button;
    }

    /** 是否贴近列表底部：只有跟随状态才自动滚动，避免把正在回看的用户拽走。 */
    boolean isFollowing() {
        return !scrolledUp;
    }

    /** 切换/清空会话时复位，避免悬浮按钮残留在新列表上。 */
    void reset() {
        scrolledUp = false;
        update();
    }

    private void attach() {
        for (Node node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                verticalBar = bar;
                bar.valueProperty().addListener(o -> {
                    scrolledUp = bar.getValue() < bar.getMax() - 0.001;
                    update();
                });
                break;
            }
        }
        update();
    }

    private void update() {
        button.setVisible(scrolledUp && !list.getItems().isEmpty());
    }
}
