package com.dp.agent.ui;

import java.awt.Desktop;
import java.net.URI;

/** 只允许 http/https，用系统默认浏览器打开，绝不在应用内加载网页。 */
final class LinkOpener {

    private LinkOpener() {
    }

    static void open(String url) {
        if (!isHttp(url)) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url.trim()));
            }
        } catch (Exception e) {
            System.err.println("[dp-agent] 打开链接失败: " + e.getMessage());
        }
    }

    /** 应用内 WebView 与外部浏览器共用的协议白名单。 */
    static boolean isHttp(String url) {
        if (url == null) {
            return false;
        }
        // 前缀判断：feed 里的链接可能含未编码的中文，URI.create 会误判。
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
