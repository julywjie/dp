package com.dp.agent.ui.markdown;

import com.dp.agent.ui.Icons;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.List;

/**
 * Markdown → JavaFX 节点。块级元素渲染为 VBox / TextFlow / GridPane 等原生节点，
 * 不引入 WebView，避免每个气泡一个浏览器内核带来的内存与滚动开销。
 *
 * <p>支持：标题、段落、加粗/斜体/删除线、行内代码、代码块、有序/无序列表、
 * 引用、分割线、链接（可点击）、GFM 表格。</p>
 */
public final class MarkdownRenderer {

    /** 助手气泡的正文宽度；外层气泡 padding 另计。 */
    public static final double CONTENT_WIDTH = 560;

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create()))
            .build();

    private MarkdownRenderer() {
    }

    /** 把 Markdown 文本渲染为可直接放入气泡的节点（永不返回 null）。 */
    public static Node render(String markdown) {
        return render(markdown, CONTENT_WIDTH);
    }

    /** @param width 正文可用宽度：随窗口宽度变化，表格/代码块超宽时横向滚动。 */
    public static Node render(String markdown, double width) {
        double contentWidth = Math.max(240, width);
        VBox root = new VBox(6);
        root.setFillWidth(true);
        root.setMaxWidth(contentWidth);
        org.commonmark.node.Node document = PARSER.parse(markdown == null ? "" : markdown);
        for (var child = document.getFirstChild(); child != null; child = child.getNext()) {
            appendBlock(root, child, contentWidth);
        }
        return root;
    }

    private static void appendBlock(VBox parent, org.commonmark.node.Node node, double width) {
        if (node instanceof Heading heading) {
            parent.getChildren().add(heading(heading, width));
        } else if (node instanceof Paragraph paragraph) {
            parent.getChildren().add(paragraph(paragraph, width));
        } else if (node instanceof BulletList list) {
            parent.getChildren().add(list(list, false, width));
        } else if (node instanceof OrderedList list) {
            parent.getChildren().add(list(list, true, width));
        } else if (node instanceof FencedCodeBlock code) {
            parent.getChildren().add(codeBlock(code.getLiteral(), code.getInfo(), width));
        } else if (node instanceof IndentedCodeBlock code) {
            parent.getChildren().add(codeBlock(code.getLiteral(), "", width));
        } else if (node instanceof BlockQuote quote) {
            parent.getChildren().add(blockQuote(quote, width));
        } else if (node instanceof ThematicBreak) {
            parent.getChildren().add(divider());
        } else if (node instanceof TableBlock table) {
            parent.getChildren().add(table(table, width));
        } else if (node instanceof HtmlBlock html) {
            parent.getChildren().add(codeBlock(html.getLiteral(), "html", width));
        } else if (node.getFirstChild() != null) {
            for (var child = node.getFirstChild(); child != null; child = child.getNext()) {
                appendBlock(parent, child, width);
            }
        }
    }

    private static TextFlow heading(Heading heading, double width) {
        TextFlow flow = textFlow(width);
        int level = Math.min(heading.getLevel(), 6);
        flow.getStyleClass().addAll("md-heading", "md-h" + level);
        InlineRenderer.appendChildren(heading, flow, InlineRenderer.Style.NORMAL.withBold());
        flow.setPadding(new Insets(4, 0, 0, 0));
        return flow;
    }

    private static TextFlow paragraph(Paragraph paragraph, double width) {
        TextFlow flow = textFlow(width);
        InlineRenderer.appendChildren(paragraph, flow, InlineRenderer.Style.NORMAL);
        return flow;
    }

    private static TextFlow textFlow(double width) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        flow.setMaxWidth(width);
        flow.getStyleClass().add("md-text-flow");
        return flow;
    }

    private static Node list(org.commonmark.node.Node listNode, boolean ordered, double width) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(0, 0, 0, 4));
        box.setMaxWidth(width);
        int number = ordered ? ((OrderedList) listNode).getStartNumber() : 0;
        for (var child = listNode.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem item)) {
                continue;
            }
            Label marker = new Label(ordered ? (number++) + "." : "•");
            marker.setMinWidth(ordered ? 24 : 14);
            marker.getStyleClass().add("md-marker");

            VBox content = new VBox(4);
            content.setFillWidth(true);
            HBox.setHgrow(content, Priority.ALWAYS);
            for (var block = item.getFirstChild(); block != null; block = block.getNext()) {
                appendBlock(content, block, width - 20);
            }

            HBox row = new HBox(6, marker, content);
            row.setAlignment(Pos.TOP_LEFT);
            box.getChildren().add(row);
        }
        return box;
    }

    /**
     * 代码块：语言标签 + 一键复制 + 只读 TextArea（等宽字体、保留缩进、可选中、超长可滚动）。
     */
    private static Node codeBlock(String literal, String language, double width) {
        String code = literal == null ? "" : literal.stripTrailing();
        Label lang = new Label(language == null || language.isBlank() ? "代码" : language.strip());
        lang.getStyleClass().add("md-code-lang");

        Button copy = new Button();
        copy.setGraphic(Icons.icon(Icons.COPY, 15));
        copy.setTooltip(new Tooltip("复制代码"));
        copy.getStyleClass().add("md-copy-button");
        copy.setOnAction(e -> {
            copyToClipboard(code);
            copy.setGraphic(Icons.icon(Icons.CHECK, 15));
            PauseTransition restore = new PauseTransition(Duration.seconds(1.2));
            restore.setOnFinished(done -> copy.setGraphic(Icons.icon(Icons.COPY, 15)));
            restore.play();
        });

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(8, lang, gap, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("md-code-header");

        TextArea area = new TextArea(code);
        area.setEditable(false);
        area.setFocusTraversable(false);
        area.setWrapText(false);
        area.setMaxWidth(Double.MAX_VALUE);
        // 多留一行：TextArea 自身的行高/内边距会吃掉一点空间，避免出现无谓的滚动条
        area.setPrefRowCount(Math.min(Math.max((int) code.lines().count() + 1, 2), 18));
        area.getStyleClass().add("md-code");

        VBox card = new VBox(header, area);
        card.setMaxWidth(width);
        card.getStyleClass().add("md-code-card");
        return card;
    }

    private static void copyToClipboard(String code) {
        ClipboardContent content = new ClipboardContent();
        content.putString(code);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static Node blockQuote(BlockQuote quote, double width) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(2, 0, 2, 12));
        box.setMaxWidth(width);
        box.getStyleClass().add("md-quote");
        for (var child = quote.getFirstChild(); child != null; child = child.getNext()) {
            appendBlock(box, child, width - 12);
        }
        return box;
    }

    private static Node divider() {
        Region line = new Region();
        line.setPrefHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        line.getStyleClass().add("md-divider");
        VBox wrapper = new VBox(line);
        wrapper.setPadding(new Insets(4, 0, 4, 0));
        return wrapper;
    }

    private static Node table(TableBlock block, double width) {
        int columns = 0;
        for (var section = block.getFirstChild(); section != null; section = section.getNext()) {
            for (var rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                if (rowNode instanceof TableRow row) {
                    int count = 0;
                    for (var cellNode = row.getFirstChild(); cellNode != null;
                         cellNode = cellNode.getNext()) {
                        if (cellNode instanceof TableCell) {
                            count++;
                        }
                    }
                    columns = Math.max(columns, count);
                }
            }
        }
        double tableWidth = Math.max(width, columns * 80.0);
        double cellWidth = Math.max(80, tableWidth / Math.max(columns, 1) - 16);

        GridPane grid = new GridPane();
        grid.setPrefWidth(tableWidth);
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.getStyleClass().add("md-table");
        int rowIndex = 0;
        for (var section = block.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = section instanceof TableHead;
            for (var rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                if (!(rowNode instanceof TableRow row)) {
                    continue;
                }
                int column = 0;
                boolean altRow = !header && rowIndex % 2 == 0;
                for (var cellNode = row.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                    if (cellNode instanceof TableCell cell) {
                        grid.add(tableCell(cell, header, altRow, cellWidth), column++, rowIndex);
                    }
                }
                rowIndex++;
            }
        }
        double percent = 100.0 / Math.max(columns, 1);
        for (int i = 0; i < columns; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(percent);
            constraints.setMinWidth(48);
            constraints.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraints);
        }
        if (tableWidth <= width + 1) {
            return grid;
        }
        // 列多时横向滚动，而不是撑破气泡
        ScrollPane scroller = new ScrollPane(grid);
        scroller.setFitToWidth(false);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPrefViewportWidth(width);
        scroller.setMaxWidth(width);
        scroller.getStyleClass().add("md-table-scroll");
        return scroller;
    }

    private static Node tableCell(TableCell cell, boolean header, boolean altRow, double width) {
        TextFlow flow = textFlow(width);
        InlineRenderer.appendChildren(cell, flow,
                header ? InlineRenderer.Style.NORMAL.withBold() : InlineRenderer.Style.NORMAL);
        StackPane wrapper = new StackPane(flow);
        wrapper.setPadding(new Insets(4, 8, 4, 8));
        wrapper.setAlignment(alignment(cell));
        flow.setTextAlignment(textAlignment(cell));
        wrapper.getStyleClass().add(header ? "md-th" : "md-td");
        if (altRow) {
            wrapper.getStyleClass().add("md-td-alt");
        }
        return wrapper;
    }

    private static Pos alignment(TableCell cell) {
        if (cell.getAlignment() == null) {
            return Pos.CENTER_LEFT;
        }
        return switch (cell.getAlignment()) {
            case CENTER -> Pos.CENTER;
            case RIGHT -> Pos.CENTER_RIGHT;
            default -> Pos.CENTER_LEFT;
        };
    }

    private static TextAlignment textAlignment(TableCell cell) {
        if (cell.getAlignment() == null) {
            return TextAlignment.LEFT;
        }
        return switch (cell.getAlignment()) {
            case CENTER -> TextAlignment.CENTER;
            case RIGHT -> TextAlignment.RIGHT;
            default -> TextAlignment.LEFT;
        };
    }
}
