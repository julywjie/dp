package com.dp.agent.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * 统一的矢量图标集（24×24 视框），避免依赖系统 emoji 字体。
 * 颜色由 app.css 的 .svg-icon 规则按宿主控件决定。
 */
public final class Icons {

    public static final String SPARK = "M12 2l2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4z";
    public static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
    public static final String SEND = "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z";
    public static final String STOP = "M6 6h12v12H6z";
    public static final String MOON = "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36"
            + "-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4C12.92 3.04 12.46 3 12 3z";
    public static final String SUN = "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2v-2H2v2z"
            + "m18 0h2v-2h-2v2zM11 2h2v2h-2V2zm0 18h2v2h-2v-2z"
            + "M4.22 4.22l1.42-1.42 1.41 1.41-1.41 1.42-1.42-1.41z"
            + "m12.73 12.73l1.41-1.41 1.42 1.41-1.42 1.42-1.41-1.42z"
            + "M4.22 19.78l1.41 1.42 1.42-1.42-1.41-1.41-1.42 1.41z"
            + "M17.95 6.05l1.41 1.41 1.42-1.41-1.42-1.42-1.41 1.42z";
    public static final String GEAR = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58"
            + "c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96"
            + "c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84"
            + "c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96"
            + "c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58"
            + "c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61"
            + "l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54"
            + "c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94"
            + "l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58z"
            + "M12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";
    public static final String WRENCH = "M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7"
            + "C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0"
            + "l2.3-2.3c.5-.4.5-1 .1-1.4z";
    public static final String BOLT = "M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12"
            + "C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z";
    public static final String COPY = "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14"
            + "c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z";
    public static final String CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    public static final String CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41"
            + " 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
    public static final String MORE = "M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"
            + "m6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"
            + "m6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z";
    public static final String WARNING = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";
    public static final String ARROW_DOWN = "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z";
    public static final String ARROW_BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
    public static final String ARROW_FORWARD = "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z";
    public static final String REFRESH = "M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6"
            + "c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";
    public static final String OPEN_EXTERNAL = "M19 19H5V5h7V3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14"
            + "c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z";
    public static final String TRASH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12z"
            + "M19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    public static final String CHART = "M3 13h4v8H3v-8zm7-6h4v14h-4V7zm7-4h4v18h-4V3z";
    public static final String BOOKMARK = "M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z";
    public static final String CODE = "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4z"
            + "m5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z";
    public static final String EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25z"
            + "M20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0"
            + "L15.13 5.12l3.75 3.75 1.83-1.83z";
    public static final String TRANSLATE = "M12.87 15.07l-2.54-2.51.03-.03c1.74-1.94 2.98-4.17 3.71-6.53H17V4h-7V2H8v2H1v2h11.17"
            + "C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56"
            + "l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12z"
            + "m-2.62 7l1.62-4.33L19.12 17h-3.24z";

    private Icons() {
    }

    /** 按目标边长缩放 24×24 图标；fill 由 CSS 的 .svg-icon 规则控制。 */
    public static Node icon(String path, double size) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("svg-icon");
        double scale = size / 24.0;
        svg.getTransforms().add(new Scale(scale, scale));
        Group group = new Group(svg);
        group.setMouseTransparent(true);
        return group;
    }
}
