package com.dp.agent.ui;

import com.dp.agent.discover.Article;
import com.dp.agent.discover.ArticleSummarizer;
import com.dp.agent.discover.DiscoverCache;
import com.dp.agent.discover.DiscoverService;
import com.dp.agent.discover.HotListSummarizer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import com.dp.agent.core.Errors;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 发现页：
 *   「AI 热榜」——只放 Hacker News / Hugging Face，各自按真实热度排序，分段呈现；
 *   「中文资讯」——Google News 中文，明确按发布时间排序，不冒充热度；
 *   点击任意条目在应用内新建 WebView 选项卡阅读。
 */
public final class DiscoverView {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_ARTICLE_TABS = 8;
    private static final int TAB_TITLE_LIMIT = 24;

    private final DiscoverService service;
    private final ListSummaryActions summaries;
    private final ArticleSummary articleSummary;
    private final SummaryCard hotSummary = new SummaryCard();
    private final SummaryCard chineseSummary = new SummaryCard();
    private final SummaryCard hotArticleSummary = new SummaryCard("文章总结");
    private final SummaryCard chineseArticleSummary = new SummaryCard("文章总结");
    private final TabPane tabs = new TabPane();
    private final VBox hotPane = new VBox();
    private final VBox chinesePane = new VBox();
    private final ListView<HotRow> hotList = new ListView<>();
    private final ListView<Article> chineseList = new ListView<>();
    private final Label hotStatus = new Label("正在准备热榜…");
    private final Label chineseStatus = new Label("正在准备资讯…");
    private final Button refreshHot = new Button("刷新");
    private final Button refreshChinese = new Button("刷新");
    private boolean loading;
    private boolean activating;
    private boolean articleSummarizing;
    private boolean closed;
    private long lastRefreshAt;
    /** 各来源最近一次成功的时间；失败时沿用旧值并在文案里标「缓存」。 */
    private long hnUpdatedAt;
    private long hfUpdatedAt;
    private long cnUpdatedAt;

    private record TabState(String url, ArticleTab page) {
    }

    /** 热榜列表的两种行：分组标题与文章。包级可见，供 {@link DiscoverCells} 渲染。 */
    record HotRow(String title, String subtitle, String kind, int count,
                  Article article, int rank) {
        static HotRow header(String title, String subtitle, String kind, int count) {
            return new HotRow(title, subtitle, kind, count, null, 0);
        }

        static HotRow article(Article article, int rank) {
            return new HotRow(null, null, null, 0, article, rank);
        }

        boolean isHeader() {
            return article == null;
        }
    }

    /** 当前选中模型对某份列表做总结；由控制器负责解析提供商与 Key。 */
    @FunctionalInterface
    public interface Summarizer {
        CompletableFuture<HotListSummarizer.Result> summarize(String listName,
                                                              List<Article> articles);
    }

    /** 当前选中模型对单篇文章做总结；正文抓取与降级由服务层处理。 */
    @FunctionalInterface
    public interface ArticleSummary {
        CompletableFuture<ArticleSummarizer.Result> summarize(Article article);
    }

    public DiscoverView(Path dataDir, Summarizer summarizer, ArticleSummary articleSummary) {
        this.service = new DiscoverService(dataDir);
        this.summaries = new ListSummaryActions(summarizer);
        this.articleSummary = articleSummary;
        build();
    }

    public Node node() {
        return tabs;
    }

    /** 每次进入发现页调用：后台读缓存，回到 FX 线程展示，再决定是否刷新。 */
    public void activate() {
        if (closed || loading || activating) {
            return;
        }
        activating = true;
        service.cachedAsync().whenComplete((cached, error) -> Platform.runLater(() -> {
            activating = false;
            if (closed) {
                return;
            }
            Optional<DiscoverCache.Snapshot> snapshot =
                    error == null ? cached : Optional.empty();
            snapshot.ifPresent(this::showSnapshot);
            boolean due = System.currentTimeMillis() - lastRefreshAt
                    > Duration.ofMinutes(5).toMillis();
            if (snapshot.isEmpty() || snapshot.get().isStale() || due) {
                refresh();
            }
        }));
    }

    public void close() {
        closed = true;
        summaries.close();
        for (Tab tab : tabs.getTabs()) {
            if (tab.getUserData() instanceof TabState state) {
                state.page().close();
            }
        }
        service.close();
    }

    private void build() {
        tabs.getTabs().add(nonClosableTab("AI 热榜", buildHotPane()));
        tabs.getTabs().add(nonClosableTab("中文资讯", buildChinesePane()));
        tabs.getStyleClass().add("discover-tabs");
    }

    private Tab nonClosableTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private VBox buildHotPane() {
        VBox heading = heading("AI 热榜",
                "Hacker News / Hugging Face · 按各来源真实热度排序");
        Button summarize = new Button();
        summaries.bind(summarize, "AI 热榜", this::hotArticles, hotStatus, hotSummary);
        HBox header = headerRow(heading, summarize, refreshHot, () -> refresh());

        hotList.getStyleClass().add("discover-list");
        hotList.setPlaceholder(placeholder(true, "正在准备热榜…"));
        hotList.setCellFactory(view -> DiscoverCells.hotCell(
                this::openArticle, article -> summarizeArticle(article, hotArticleSummary)));
        hotList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                HotRow selected = hotList.getSelectionModel().getSelectedItem();
                if (selected != null && !selected.isHeader()) {
                    openArticle(selected.article());
                    e.consume();
                }
            }
        });
        VBox.setVgrow(hotList, Priority.ALWAYS);

        hotStatus.getStyleClass().add("discover-status");
        Label footer = footer("来源：Hacker News · Hugging Face");
        VBox.setMargin(hotSummary.node(), new Insets(0, 20, 6, 20));
        VBox.setMargin(hotArticleSummary.node(), new Insets(0, 20, 6, 20));
        hotPane.getChildren().addAll(header, hotSummary.node(), hotArticleSummary.node(),
                hotList, bottom(hotStatus, footer));
        hotPane.getStyleClass().add("discover-root");
        return hotPane;
    }

    private VBox buildChinesePane() {
        VBox heading = heading("中文 AI 资讯", "按发布时间排序 · 不代表热度");
        Button summarize = new Button();
        summaries.bind(summarize, "中文资讯", this::chineseArticles, chineseStatus, chineseSummary);
        HBox header = headerRow(heading, summarize, refreshChinese, () -> refresh());

        chineseList.getStyleClass().add("discover-list");
        chineseList.setPlaceholder(placeholder(true, "正在准备资讯…"));
        chineseList.setCellFactory(view -> DiscoverCells.chineseCell(
                this::openArticle, article -> summarizeArticle(article, chineseArticleSummary)));
        chineseList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                Article article = chineseList.getSelectionModel().getSelectedItem();
                if (article != null) {
                    openArticle(article);
                    e.consume();
                }
            }
        });
        VBox.setVgrow(chineseList, Priority.ALWAYS);

        chineseStatus.getStyleClass().add("discover-status");
        Label footer = footer("来源：Google News 中文（个人阅读用途）");
        VBox.setMargin(chineseSummary.node(), new Insets(0, 20, 6, 20));
        VBox.setMargin(chineseArticleSummary.node(), new Insets(0, 20, 6, 20));
        chinesePane.getChildren().addAll(header, chineseSummary.node(),
                chineseArticleSummary.node(), chineseList, bottom(chineseStatus, footer));
        chinesePane.getStyleClass().add("discover-root");
        return chinesePane;
    }

    private VBox heading(String titleText, String subtitleText) {
        Label title = new Label(titleText);
        title.getStyleClass().add("discover-title");
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("discover-subtitle");
        return new VBox(3, title, subtitle);
    }

    private HBox headerRow(VBox heading, Button summarizeButton,
                           Button refreshButton, Runnable action) {
        refreshButton.setText("刷新");
        refreshButton.setGraphic(Icons.icon(Icons.REFRESH, 13));
        refreshButton.setContentDisplay(ContentDisplay.LEFT);
        refreshButton.setGraphicTextGap(5);
        refreshButton.getStyleClass().add("ghost-button");
        refreshButton.setTooltip(new Tooltip("刷新全部来源（热榜与中文资讯共用一次请求）"));
        refreshButton.setOnAction(e -> action.run());
        HBox header = new HBox(8, heading, spacer(), summarizeButton, refreshButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("discover-header");
        return header;
    }

    private VBox placeholder(boolean loading, String loadingTitle) {
        StackPane orb = new StackPane(Icons.icon(loading ? Icons.REFRESH : Icons.SPARK, 20));
        orb.getStyleClass().add("discover-empty-orb");

        Label title = new Label(loading ? loadingTitle : "暂时没有内容");
        title.getStyleClass().add("discover-placeholder-title");

        Label hint = new Label(loading
                ? "正在从各来源拉取最新内容"
                : "点击右上角「刷新」重试");
        hint.getStyleClass().add("discover-placeholder");
        hint.setWrapText(true);
        hint.setTextAlignment(TextAlignment.CENTER);
        hint.setMaxWidth(360);

        VBox box = new VBox(8, orb, title, hint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("discover-empty");
        if (loading) {
            ProgressIndicator spinner = new ProgressIndicator(-1);
            spinner.setPrefSize(16, 16);
            spinner.setMaxSize(16, 16);
            spinner.getStyleClass().add("discover-spinner");
            box.getChildren().add(spinner);
        }
        return box;
    }

    private VBox bottom(Label status, Label footer) {
        VBox box = new VBox(3, status, footer);
        box.getStyleClass().add("discover-bottom");
        return box;
    }

    private Label footer(String text) {
        Label footer = new Label(text);
        footer.getStyleClass().add("discover-footer");
        return footer;
    }

    private void showSnapshot(DiscoverCache.Snapshot snapshot) {
        boolean hotWasEmpty = hotList.getItems().isEmpty();
        boolean chineseWasEmpty = chineseList.getItems().isEmpty();
        hotList.getItems().setAll(DiscoverCells.buildRows(
                snapshot.hotHackerNews(), snapshot.hotHuggingFace()));
        chineseList.getItems().setAll(snapshot.chinese());
        // 从文章页返回时保留滚动位置，只有首次填充才回到顶部。
        if (hotWasEmpty) {
            hotList.scrollTo(0);
        }
        if (chineseWasEmpty) {
            chineseList.scrollTo(0);
        }
        hnUpdatedAt = snapshot.fetchedAt();
        hfUpdatedAt = snapshot.fetchedAt();
        cnUpdatedAt = snapshot.fetchedAt();
        updateStatus(false, false, false,
                size(snapshot.hotHackerNews()) + size(snapshot.hotHuggingFace()),
                size(snapshot.chinese()), "");
    }

    private void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        refreshHot.setDisable(true);
        refreshChinese.setDisable(true);
        hotStatus.setText("刷新中…");
        chineseStatus.setText("刷新中…");
        hotList.setPlaceholder(placeholder(true, "正在准备热榜…"));
        chineseList.setPlaceholder(placeholder(true, "正在准备资讯…"));

        service.refresh().whenComplete((result, error) -> Platform.runLater(() -> {
            loading = false;
            refreshHot.setDisable(false);
            refreshChinese.setDisable(false);
            if (error != null) {
                showRefreshFailure();
                return;
            }
            hotList.getItems().setAll(DiscoverCells.buildRows(
                    result.hotHackerNews(), result.hotHuggingFace()));
            chineseList.getItems().setAll(result.chinese());
            hotList.setPlaceholder(placeholder(false, "正在准备热榜…"));
            chineseList.setPlaceholder(placeholder(false, "正在准备资讯…"));
            boolean hnFailed = sourceFailed(result, "Hacker News");
            boolean hfFailed = sourceFailed(result, "Hugging Face");
            boolean cnFailed = sourceFailed(result, "Google News 中文");
            long stamp = result.fetchedAt();
            if (!hnFailed) {
                hnUpdatedAt = stamp;
            }
            if (!hfFailed) {
                hfUpdatedAt = stamp;
            }
            if (!cnFailed) {
                cnUpdatedAt = stamp;
            }
            String failed = result.failedSources().isEmpty()
                    ? "" : " · 部分来源不可用：" + String.join(" / ", result.failedSources());
            boolean anySuccess = result.failedSources().size() < 3;
            if (anySuccess) {
                lastRefreshAt = System.currentTimeMillis();
            } else {
                failed = " · 刷新失败，显示上次缓存";
            }
            updateStatus(hnFailed, hfFailed, cnFailed,
                    result.hotHackerNews().size() + result.hotHuggingFace().size(),
                    result.chinese().size(), failed);
        }));
    }

    private static boolean sourceFailed(DiscoverService.RefreshResult result, String label) {
        return result.failedSources().stream().anyMatch(s -> s.startsWith(label));
    }

    private void showRefreshFailure() {
        boolean hasContent = !hotList.getItems().isEmpty() || !chineseList.getItems().isEmpty();
        String text = hasContent ? "刷新失败，显示上次缓存" : "刷新失败，请检查网络后重试";
        hotStatus.setText(text);
        chineseStatus.setText(text);
        hotList.setPlaceholder(placeholder(false, "正在准备热榜…"));
        chineseList.setPlaceholder(placeholder(false, "正在准备资讯…"));
    }

    private void updateStatus(boolean hnFailed, boolean hfFailed, boolean cnFailed,
                              int hotCount, int chineseCount, String failed) {
        String hn = "HN " + timeOrDash(hnUpdatedAt) + (hnFailed ? "（缓存）" : "");
        String hf = "HF " + timeOrDash(hfUpdatedAt) + (hfFailed ? "（缓存）" : "");
        hotStatus.setText(hn + " · " + hf + " · " + hotCount + " 条" + failed);
        chineseStatus.setText("中文 " + timeOrDash(cnUpdatedAt)
                + (cnFailed ? "（缓存）" : "") + " · " + chineseCount + " 条" + failed);
    }

    private static String timeOrDash(long epochMillis) {
        return epochMillis > 0 ? timeText(epochMillis) : "--:--";
    }

    private List<Article> hotArticles() {
        return hotList.getItems().stream()
                .filter(row -> !row.isHeader())
                .map(HotRow::article)
                .toList();
    }

    private List<Article> chineseArticles() {
        return List.copyOf(chineseList.getItems());
    }

    private void summarizeArticle(Article article, SummaryCard card) {
        if (articleSummarizing) {
            return;
        }
        articleSummarizing = true;
        String label = abbreviate(article.title(), 90);
        card.showLoading(label, "正在阅读并总结正文…");

        CompletableFuture<ArticleSummarizer.Result> future;
        try {
            future = articleSummary.summarize(article);
        } catch (RuntimeException e) {
            articleSummarizing = false;
            card.showError("文章总结失败",
                    Errors.userMessage(e, "模型调用失败，请稍后重试。", 300));
            return;
        }
        future.whenComplete((result, error) -> Platform.runLater(() -> {
            articleSummarizing = false;
            if (closed) {
                return;
            }
            if (error != null) {
                card.showError("文章总结失败",
                        Errors.userMessage(error, "模型调用失败，请稍后重试。", 300));
                return;
            }
            if (result == null || result.text() == null || result.text().isBlank()) {
                card.showError("文章总结失败", "模型未返回总结内容，请重试。");
                return;
            }
            card.showResult(label, result.text(), result.modelName(),
                    article.url(), !result.contentBased(), result.note());
        }));
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.strip().replaceAll("\\s+", " ");
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private void openArticle(Article article) {
        if (!LinkOpener.isHttp(article.url())) {
            hotStatus.setText("链接已阻止：仅支持 http/https");
            chineseStatus.setText("链接已阻止：仅支持 http/https");
            return;
        }
        for (Tab tab : tabs.getTabs()) {
            if (tab.getUserData() instanceof TabState state && state.url().equals(article.url())) {
                tabs.getSelectionModel().select(tab);
                return;
            }
        }
        ArticleTab page = new ArticleTab(article);
        Tab tab = new Tab(shortTitle(article.title()), page.node());
        tab.setUserData(new TabState(article.url(), page));
        tab.setClosable(true);
        tab.setOnClosed(e -> page.close());
        page.titleProperty().addListener((obs, old, title) -> {
            if (title != null && !title.isBlank()) {
                tab.setText(shortTitle(title));
            }
        });
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);

        // 只统计可关闭的文章标签；淘汰最早打开且不是当前阅读的那一个。
        while (tabs.getTabs().size() - 2 > MAX_ARTICLE_TABS) {
            Tab victim = null;
            for (Tab candidate : tabs.getTabs()) {
                if (candidate.getUserData() instanceof TabState
                        && candidate != tabs.getSelectionModel().getSelectedItem()) {
                    victim = candidate;
                    break;
                }
            }
            if (victim == null) {
                break;
            }
            closeArticleTab(victim);
        }
    }

    private void closeArticleTab(Tab tab) {
        if (tab.getUserData() instanceof TabState state) {
            state.page().close();
        }
        tabs.getTabs().remove(tab);
    }

    private static int size(List<Article> articles) {
        return articles == null ? 0 : articles.size();
    }

    private static String shortTitle(String title) {
        if (title == null || title.isBlank()) {
            return "未命名页面";
        }
        String text = title.trim().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) <= TAB_TITLE_LIMIT) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, TAB_TITLE_LIMIT)) + "…";
    }

    private static String timeText(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                .toLocalTime().format(CLOCK);
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

}
