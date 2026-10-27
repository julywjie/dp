package com.dp.agent.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;
import java.util.List;

/**
 * 窗口与任务栏图标：多档 PNG 一起交给 Stage，由系统按 DPI/场景挑选，
 * 避免单张大图缩小后发虚或小图放大后模糊。
 */
public final class AppIcon {

    private static final List<String> SIZES = List.of("16", "24", "32", "48", "64", "128", "256");

    private AppIcon() {
    }

    public static void apply(Stage stage) {
        for (String size : SIZES) {
            URL url = AppIcon.class.getResource("/com/dp/agent/ui/icon/dp-agent-" + size + ".png");
            if (url != null) {
                stage.getIcons().add(new Image(url.toExternalForm()));
            }
        }
    }
}
