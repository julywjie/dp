package com.dp.agent.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * 轻量入场动效：淡入 + 轻微上移。
 * 列表单元格会被复用，调用方须保证同一条消息只播一次。
 */
final class Motion {

    private static final Duration ENTER_DURATION = Duration.millis(220);
    private static final double ENTER_OFFSET = 8;

    private Motion() {
    }

    static void enter(Node node) {
        node.setOpacity(0);
        node.setTranslateY(ENTER_OFFSET);

        FadeTransition fade = new FadeTransition(ENTER_DURATION, node);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(ENTER_DURATION, node);
        slide.setFromY(ENTER_OFFSET);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, slide).play();
    }
}
