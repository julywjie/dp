package com.dp.agent;

import javafx.application.Application;

/**
 * 独立启动器：主类不能直接 extends Application，否则在 classpath
 * 方式启动（java -cp）下会报"缺少 JavaFX 运行时组件"。
 * mvn javafx:run 与 java -cp 两种方式均可经由本类启动。
 */
public final class Launcher {

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }

    private Launcher() {
    }
}
