package com.dp.agent.ui;

import com.dp.agent.discover.Article;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 发现页列表行渲染：分组标题、文章卡片与两种 ListCell。 */
final class DiscoverCells {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");

    private DiscoverCells() {
    }

    static ListCell<DiscoverView.HotRow> hotCell(Consumer<Article> open,
                                                 Consumer<Article> summarize) {
        return new ListCell<>() {
            @Override
            protected void updateItem(DiscoverView.HotRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                if (row.isHeader()) {
                    Label badge = new Label("hn".equals(row.kind()) ? "HN" : "HF");
                    badge.getStyleClass().addAll("source-badge", row.kind());
                    Label title = new Label(row.title());
                    title.getStyleClass().add("hot-section-title");
                    Label count = new Label(row.count() + " 条");
                    count.getStyleClass().add("section-count");
                    HBox titleRow = new HBox(8, badge, title, count);
                    titleRow.setAlignment(Pos.CENTER_LEFT);
                    Label subtitle = new Label(row.subtitle());
                    subtitle.getStyleClass().add("hot-section-subtitle");
                    VBox header = new VBox(3, titleRow, subtitle);
                    header.getStyleClass().add("discover-section-header");
                    setText(null);
                    setGraphic(header);
                    setTooltip(null);
                    return;
                }
                setText(null);
                setGraphic(articleCard(row.article(), row.rank(), open, summarize));
                setTooltip(new Tooltip("在应用内打开\n" + row.article().url()));
            }
        };
    }

    static ListCell<Article> chineseCell(Consumer<Article> open,
                                         Consumer<Article> summarize) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Article article, boolean empty) {
                super.updateItem(article, empty);
                if (empty || article == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                setText(null);
                setGraphic(articleCard(article, getIndex() + 1, open, summarize));
                setTooltip(new Tooltip("在应用内打开\n" + article.url()));
            }
        };
    }

    static List<DiscoverView.HotRow> buildRows(List<Article> hackerNews,
                                               List<Article> huggingFace) {
        List<DiscoverView.HotRow> rows = new ArrayList<>();
        rows.add(DiscoverView.HotRow.header("Hacker News 热帖", "points + 评论 · 近 7 天",
                "hn", hackerNews.size()));
        int rank = 1;
        for (Article article : hackerNews) {
            rows.add(DiscoverView.HotRow.article(article, rank++));
        }
        rows.add(DiscoverView.HotRow.header("Hugging Face 热门论文", "upvotes",
                "hf", huggingFace.size()));
        rank = 1;
        for (Article article : huggingFace) {
            rows.add(DiscoverView.HotRow.article(article, rank++));
        }
        return rows;
    }

    private static HBox articleCard(Article article, int rank, Consumer<Article> open,
                                    Consumer<Article> summarize) {
        Label rankLabel = new Label(String.valueOf(rank));
        rankLabel.getStyleClass().add("article-rank");
        if (rank <= 3) {
            rankLabel.getStyleClass().add("top");
        }
        Label title = new Label(article.title());
        title.getStyleClass().add("article-title");
        title.setWrapText(true);
        Label badge = new Label(badgeText(article.sourceKey()));
        badge.getStyleClass().addAll("source-badge", article.sourceKey());
        String metaText = "cn".equals(article.sourceKey())
                ? article.metric() + " · " + relativeTime(article.publishedAt())
                : article.source() + " · " + article.metric()
                        + " · " + relativeTime(article.publishedAt());
        Label meta = new Label(metaText);
        meta.getStyleClass().add("article-meta");

        HBox metaRow = new HBox(8, badge, meta);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(4, title, metaRow);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button summary = new Button("总结");
        summary.setGraphic(Icons.icon(Icons.SPARK, 13));
        summary.setContentDisplay(ContentDisplay.LEFT);
        summary.setGraphicTextGap(4);
        summary.getStyleClass().add("article-summary-btn");
        summary.setTooltip(new Tooltip("总结这篇文章"));
        summary.setAccessibleText("总结这篇文章：" + article.title());
        summary.setOnAction(event -> {
            event.consume();
            summarize.accept(article);
        });
        HBox.setMargin(summary, new Insets(2, 0, 0, 0));

        HBox card = new HBox(10, rankLabel, text, summary);
        card.setAlignment(Pos.TOP_LEFT);
        card.getStyleClass().addAll("article-cell", article.sourceKey());
        card.setAccessibleText(article.title() + " · " + article.source());
        card.setOnMouseClicked(event -> {
            // 点击总结按钮/图标时不要顺带打开文章
            if (contains(summary, event.getTarget())) {
                return;
            }
            open.accept(article);
        });
        return card;
    }

    private static boolean contains(Node ancestor, Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static String badgeText(String sourceKey) {
        return switch (sourceKey) {
            case "hn" -> "HN";
            case "hf" -> "HF";
            default -> "中文";
        };
    }

    private static String relativeTime(long epochMillis) {
        long minutes = Duration.ofMillis(
                Math.max(0, System.currentTimeMillis() - epochMillis)).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days == 1) {
            return "昨天";
        }
        if (days < 7) {
            return days + " 天前";
        }
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                .toLocalDate().format(DAY);
    }
}
