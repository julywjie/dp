package com.dp.agent.ui;

import com.dp.agent.discover.Article;
import javafx.animation.FadeTransition;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;

import java.net.URI;

/**
 * 单个文章选项卡：应用内 WebView 阅读，带前进/后退/刷新/外部浏览器兜底。
 * 加载期间用主题色遮罩盖住 WebView 的白色空白，加载完成淡出。
 */
final class ArticleTab {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final VBox root = new VBox();
    private final Button back = iconButton(Icons.ARROW_BACK, "后退");
    private final Button forward = iconButton(Icons.ARROW_FORWARD, "前进");
    private final Button reload = iconButton(Icons.REFRESH, "刷新");
    private final Button readerBtn = iconButton(Icons.MOON, "深色阅读");
    private final ProgressBar progress = new ProgressBar();
    private final Label status = new Label("加载中…");
    private final Label host = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label loadingTitle = new Label();
    private final Label loadingHint = new Label();
    private final VBox overlay;
    private final ReadOnlyStringWrapper pageTitle = new ReadOnlyStringWrapper("");
    private final String url;
    private final String articleTitle;
    private boolean readerDark;
    private boolean readerInitialized;

    ArticleTab(Article article) {
        this.url = article.url();
        this.articleTitle = article.title();
        webView.getStyleClass().add("article-web");
        engine.setUserAgent(USER_AGENT);
        engine.titleProperty().addListener((obs, old, title) -> {
            if (title != null && !title.isBlank()) {
                pageTitle.set(title);
            }
        });
        engine.locationProperty().addListener((obs, old, location) -> {
            String domain = domain(location);
            if (!domain.isBlank()) {
                host.setText(domain);
            }
        });

        host.setText(domain(url));
        host.getStyleClass().add("article-host");
        host.setTextOverrun(OverrunStyle.ELLIPSIS);
        host.setMaxWidth(220);
        wireNavigation();
        wireLoadingState();

        progress.getStyleClass().add("article-progress");
        progress.setPrefWidth(72);
        progress.progressProperty().bind(engine.getLoadWorker().progressProperty());
        progress.setVisible(false);
        status.getStyleClass().add("article-status");

        Button external = iconButton(Icons.OPEN_EXTERNAL, "在浏览器中打开");
        external.setOnAction(e -> LinkOpener.open(currentUrl()));
        readerBtn.setOnAction(e -> toggleReader());
        updateReaderButton();

        HBox toolbar = new HBox(4, back, forward, reload, progress, status,
                spacer(), host, readerBtn, external);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("article-toolbar");

        overlay = buildOverlay(article);
        StackPane webArea = new StackPane(webView, overlay);
        VBox.setVgrow(webArea, Priority.ALWAYS);

        root.getChildren().addAll(toolbar, webArea);
        root.getStyleClass().add("article-page");
        if (LinkOpener.isHttp(url)) {
            engine.load(url);
        } else {
            showBlocked();
        }
    }

    /** 缓存/来源数据不可信：非 http(s) 链接一律不交给 WebView。 */
    private void showBlocked() {
        spinner.setVisible(false);
        loadingTitle.setText("链接已阻止");
        loadingHint.setText("只允许打开 http / https 页面");
        status.setText("已阻止");
        overlay.setVisible(true);
        overlay.setMouseTransparent(false);
        overlay.setOpacity(1);
    }

    Node node() {
        return root;
    }

    ReadOnlyStringProperty titleProperty() {
        return pageTitle.getReadOnlyProperty();
    }

    void close() {
        engine.getLoadWorker().cancel();
        engine.load("about:blank");
    }

    private VBox buildOverlay(Article article) {
        Label badge = new Label(badgeText(article.sourceKey()));
        badge.getStyleClass().addAll("source-badge", article.sourceKey());
        spinner.setPrefSize(30, 30);
        spinner.getStyleClass().add("article-spinner");
        loadingTitle.setText(articleTitle);
        loadingTitle.setWrapText(true);
        loadingTitle.setMaxWidth(620);
        loadingTitle.setAlignment(Pos.CENTER);
        loadingTitle.setTextAlignment(TextAlignment.CENTER);
        loadingTitle.getStyleClass().add("article-loading-title");
        loadingHint.setText("正在加载 · " + host.getText());
        loadingHint.getStyleClass().add("article-loading-hint");

        VBox box = new VBox(12, badge, spinner, loadingTitle, loadingHint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("article-loading");
        return box;
    }

    private void wireNavigation() {
        back.setOnAction(e -> {
            if (engine.getHistory().getCurrentIndex() > 0) {
                engine.getHistory().go(-1);
            }
        });
        forward.setOnAction(e -> {
            var history = engine.getHistory();
            if (history.getCurrentIndex() < history.getEntries().size() - 1) {
                history.go(1);
            }
        });
        reload.setOnAction(e -> engine.reload());
        engine.getHistory().currentIndexProperty().addListener(
                (obs, old, index) -> updateNav());
        updateNav();
    }

    private void wireLoadingState() {
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            boolean running = state == Worker.State.SCHEDULED || state == Worker.State.RUNNING;
            progress.setVisible(running);
            reload.setDisable(running);
            if (state == Worker.State.SCHEDULED || state == Worker.State.RUNNING) {
                showOverlay();
                status.setVisible(true);
                status.setText("加载中…");
            } else if (state == Worker.State.SUCCEEDED) {
                if (!readerInitialized) {
                    readerInitialized = true;
                    readerDark = isAppDark();
                    updateReaderButton();
                }
                applyReaderTheme();
                status.setVisible(false);
                hideOverlay();
            } else if (state == Worker.State.FAILED) {
                status.setText("加载失败");
                spinner.setVisible(false);
                loadingTitle.setText("页面加载失败");
                loadingHint.setText("可在工具栏右侧用浏览器打开");
                overlay.setVisible(true);
                overlay.setMouseTransparent(false);
                overlay.setOpacity(1);
            } else if (state == Worker.State.CANCELLED) {
                status.setText("已停止加载");
            }
        });
    }

    /** 深色阅读：注入一段阅读样式，不修改站点结构；浅色主题默认关闭。 */
    private void toggleReader() {
        readerDark = !readerDark;
        readerInitialized = true;
        updateReaderButton();
        applyReaderTheme();
    }

    private void updateReaderButton() {
        readerBtn.setGraphic(Icons.icon(readerDark ? Icons.SUN : Icons.MOON, 15));
        readerBtn.setAccessibleText(readerDark ? "关闭深色阅读" : "开启深色阅读");
        readerBtn.setTooltip(new Tooltip(readerDark
                ? "深色阅读：开（点击切回原色）" : "深色阅读：关"));
    }

    private boolean isAppDark() {
        return webView.getScene() != null
                && webView.getScene().getRoot().getStyleClass().contains("theme-dark");
    }

    private void applyReaderTheme() {
        if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            return;
        }
        try {
            engine.executeScript(readerDark ? DARK_READER_JS : REMOVE_READER_JS);
        } catch (RuntimeException ignored) {
            // 页面禁用脚本或正在跳转：保持原样即可
        }
    }

    private static final String DARK_READER_JS = """
            (function(){
              var id='dp-dark-reader';
              var s=document.getElementById(id);
              if(!s){s=document.createElement('style');s.id=id;document.documentElement.appendChild(s);}
              s.textContent='html,body{background:#0f172a !important;color:#dbe4f0 !important;}'
                +'body *{background-color:transparent !important;color:#dbe4f0 !important;'
                + 'border-color:#2a3a54 !important;}'
                +'a,a *{color:#7dd3fc !important;}'
                +'input,textarea,select,button{background:#16213a !important;color:#dbe4f0 !important;}'
                +'img,video,svg,canvas,iframe{background-color:transparent !important;}'
                +'html{color-scheme:dark !important;}';
            })();
            """;

    private static final String REMOVE_READER_JS =
            "(function(){var s=document.getElementById('dp-dark-reader');if(s){s.remove();}})();";

    private void showOverlay() {
        spinner.setVisible(true);
        loadingTitle.setText(articleTitle == null || articleTitle.isBlank()
                ? "正在加载页面" : articleTitle);
        loadingHint.setText("正在加载 · " + host.getText());
        overlay.setOpacity(1);
        overlay.setVisible(true);
        overlay.setMouseTransparent(false);
    }

    private void hideOverlay() {
        FadeTransition fade = new FadeTransition(Duration.millis(200), overlay);
        fade.setFromValue(overlay.getOpacity());
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            overlay.setVisible(false);
            overlay.setMouseTransparent(true);
            overlay.setOpacity(1);
        });
        fade.play();
    }

    private void updateNav() {
        var history = engine.getHistory();
        back.setDisable(history.getCurrentIndex() <= 0);
        forward.setDisable(history.getCurrentIndex() >= history.getEntries().size() - 1);
    }

    private String currentUrl() {
        String location = engine.getLocation();
        return location == null || location.isBlank() ? url : location;
    }

    private static String domain(String url) {
        try {
            URI uri = URI.create(url);
            String value = uri.getHost();
            if (value == null) {
                return "";
            }
            return value.startsWith("www.") ? value.substring(4) : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static String badgeText(String sourceKey) {
        return switch (sourceKey) {
            case "hn" -> "Hacker News";
            case "hf" -> "Hugging Face";
            default -> "中文资讯";
        };
    }

    private static Button iconButton(String path, String tooltip) {
        Button button = new Button();
        button.setGraphic(Icons.icon(path, 15));
        button.getStyleClass().addAll("icon-button", "article-nav");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
