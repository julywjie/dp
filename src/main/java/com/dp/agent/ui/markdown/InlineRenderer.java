package com.dp.agent.ui.markdown;

import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;

import java.awt.Desktop;
import java.net.URI;

/**
 * 把 Markdown 的行内节点（文本/加粗/斜体/行内代码/链接/换行…）追加到 TextFlow。
 * 只处理行内层；颜色、字体等样式全部由 app.css 的 md-* 样式类决定。
 */
final class InlineRenderer {

    /** 可叠加的行内样式。 */
    record Style(boolean bold, boolean italic, boolean strike, boolean link) {

        static final Style NORMAL = new Style(false, false, false, false);

        Style withBold() {
            return new Style(true, italic, strike, link);
        }

        Style withItalic() {
            return new Style(bold, true, strike, link);
        }

        Style withStrike() {
            return new Style(bold, italic, true, link);
        }

        Style withLink() {
            return new Style(bold, italic, strike, true);
        }
    }

    private InlineRenderer() {
    }

    /** 递归渲染 parent 的全部子节点（parent 可以是 Paragraph / Heading / TableCell…）。 */
    static void appendChildren(org.commonmark.node.Node parent, TextFlow flow, Style style) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNext()) {
            append(child, flow, style);
        }
    }

    private static void append(org.commonmark.node.Node node, TextFlow flow, Style style) {
        if (node instanceof org.commonmark.node.Text text) {
            flow.getChildren().add(textNode(text.getLiteral(), style));
        } else if (node instanceof StrongEmphasis strong) {
            appendChildren(strong, flow, style.withBold());
        } else if (node instanceof Emphasis emphasis) {
            appendChildren(emphasis, flow, style.withItalic());
        } else if (node instanceof Strikethrough strike) {
            appendChildren(strike, flow, style.withStrike());
        } else if (node instanceof Code code) {
            flow.getChildren().add(inlineCode(code.getLiteral()));
        } else if (node instanceof Link link) {
            flow.getChildren().add(linkNode(link, style));
        } else if (node instanceof Image image) {
            String alt = literalOf(image).strip();
            String label = alt.isEmpty() && image.getDestination() != null
                    ? image.getDestination() : alt;
            flow.getChildren().add(textNode("🖼 " + label, style.withItalic()));
        } else if (node instanceof SoftLineBreak
                || node instanceof HardLineBreak) {
            flow.getChildren().add(textNode("\n", style));
        } else if (node instanceof HtmlInline) {
            // 原始 HTML 不做渲染，直接略过，避免把标签当正文展示
        } else {
            appendChildren(node, flow, style);
        }
    }

    private static Text textNode(String literal, Style style) {
        Text text = new Text(literal);
        text.getStyleClass().add("md-text");
        if (style.bold()) {
            text.getStyleClass().add("md-strong");
        }
        if (style.italic()) {
            text.getStyleClass().add("md-em");
        }
        if (style.strike()) {
            text.getStyleClass().add("md-strike");
        }
        return text;
    }

    /** 行内代码用 Label：可以带浅色胶囊背景，比纯改色更有层次。 */
    private static Label inlineCode(String literal) {
        Label label = new Label(literal);
        label.getStyleClass().add("md-code-inline");
        return label;
    }

    /** 链接用带点击事件的 Text：字体与基线随上下文自然继承，不影响行内排版。 */
    private static Text linkNode(Link link, Style style) {
        String destination = link.getDestination() == null ? "" : link.getDestination();
        String label = literalOf(link).strip();
        Text text = textNode(label.isEmpty() ? destination : label, style.withLink());
        text.getStyleClass().add("md-link");
        text.setCursor(Cursor.HAND);
        if (isSafeUrl(destination)) {
            text.setOnMouseClicked(e -> openInBrowser(destination));
        }
        return text;
    }

    private static String literalOf(org.commonmark.node.Node parent) {
        StringBuilder sb = new StringBuilder();
        for (var child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text text) {
                sb.append(text.getLiteral());
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                sb.append(' ');
            } else if (!(child instanceof HtmlInline)) {
                sb.append(literalOf(child));
            }
        }
        return sb.toString();
    }

    /** 只放行常见协议，防止模型输出 file: / javascript: 等危险链接。 */
    private static boolean isSafeUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:");
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // 浏览器打开失败不影响消息展示
        }
    }
}
