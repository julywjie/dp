package com.dp.agent.ui;

import com.dp.agent.market.MarketBreadth;
import com.dp.agent.market.MarketQuote;
import com.dp.agent.market.MarketService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 右下角行情浮窗：默认收缩为「行情」按钮，点击后展开十行指标。
 *
 * 指数每 3 秒、汇率与涨跌家数每 30 秒后台刷新；收缩时暂停请求，展开瞬间立即补一次。
 * 网络失败时保留上一份数据并标记为“离线”，不把旧值伪装成实时值。
 */
final class MarketTicker {

    private static final int REFRESH_SECONDS = 3;
    /** 中证2000 正常也按 3 秒刷新；源站异常时指数退避，避免触发风控。 */
    private static final long CSI_BACKOFF_BASE_MS = 3_000;
    private static final long CSI_MAX_BACKOFF_MS = 300_000;
    /** 涨跌家数与汇率变化慢，30 秒足够，也降低源站接口的压力。 */
    private static final long BREADTH_INTERVAL_MS = 30_000;
    private static final long FX_INTERVAL_MS = 30_000;
    /** 富时中国A50 / 日经225 / 韩国KOSPI：东方财富单指数接口，30 秒一档，避免对免费接口请求过密。 */
    private static final long OVERSEAS_INTERVAL_MS = 30_000;
    private static final List<String> CORE_KEYS =
            List.of("sh000001", "hstech", "hs300", "chinext", "star50");
    private static final List<String> OVERSEAS_KEYS =
            List.of("ftseA50", "nikkei225", "kospi");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record RowSpec(String key, String label, boolean forex) {
    }

    private static final List<RowSpec> ROWS = List.of(
            new RowSpec("sh000001", "上证指数", false),
            new RowSpec("hstech", "恒生科技", false),
            new RowSpec("ftseA50", "富时中国A50", false),
            new RowSpec("nikkei225", "日经225", false),
            new RowSpec("kospi", "韩国KOSPI", false),
            new RowSpec("hs300", "沪深300", false),
            new RowSpec("chinext", "创业板指", false),
            new RowSpec("star50", "科创50", false),
            new RowSpec("csi2000", "中证2000", false),
            new RowSpec(MarketService.USDCNH_KEY, "美元离岸人民币", true)
    );

    private final MarketService service = new MarketService();
    private final VBox root = new VBox();
    private final VBox card = new VBox();
    private final Button toggle = new Button("行情");
    private final Button refresh = new Button();
    private final Label updated = new Label("--:--");
    private final Label breadthUp = new Label("涨 —");
    private final Label breadthDown = new Label("跌 —");
    private final Label breadthFlat = new Label("平 —");
    private final HBox breadthRow = new HBox(8);
    private final Tooltip breadthTip =
            new Tooltip("沪深北三市涨跌家数，约 30 秒刷新（数据来源：东方财富）");
    private boolean breadthLoaded;
    private final Map<String, MarketQuoteRow> rows = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "market-quotes");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean fetching = new AtomicBoolean();
    private volatile boolean closed;
    /** FX 线程写、调度线程读，必须 volatile。 */
    private volatile boolean expanded;
    /** 手动刷新节流，仅 FX 线程访问。 */
    private long lastManualRefresh;
    /** 仅调度线程读写：各数据源的下一次请求时间点与连续失败次数。 */
    private volatile long csiNextAt;
    private int csiFailures;
    private volatile long coreNextAt;
    private int coreFailures;
    private volatile long overseasNextAt;
    private int overseasFailures;
    private volatile long breadthNextAt;
    private int breadthFailures;
    private volatile long fxNextAt;
    private int fxFailures;

    MarketTicker() {
        buildToggle();
        buildCard();
        root.setAlignment(Pos.BOTTOM_RIGHT);
        root.setMaxWidth(Region.USE_PREF_SIZE);
        root.setMaxHeight(Region.USE_PREF_SIZE);
        root.getChildren().setAll(toggle);
        scheduler.scheduleWithFixedDelay(this::tick,
                0, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    VBox node() {
        return root;
    }

    void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    private void buildToggle() {
        toggle.setGraphic(Icons.icon(Icons.CHART, 14));
        toggle.setContentDisplay(ContentDisplay.LEFT);
        toggle.setGraphicTextGap(5);
        toggle.getStyleClass().add("market-toggle");
        toggle.setAccessibleText("显示行情");
        toggle.setTooltip(new Tooltip(
                "显示行情（上证 / 恒生科技 / 富时中国A50 / 日经225 / 韩国KOSPI / 沪深300 / 创业板指 / 科创50 / 中证2000 / 美元离岸人民币）"));
        toggle.setOnAction(e -> expand());
    }

    private void buildCard() {
        Label title = new Label("行情");
        title.getStyleClass().add("market-title");
        updated.getStyleClass().add("market-updated");

        refresh.setGraphic(Icons.icon(Icons.REFRESH, 12));
        refresh.getStyleClass().add("market-refresh");
        refresh.setAccessibleText("立即刷新行情");
        refresh.setTooltip(new Tooltip("立即刷新（跳过退避，最短间隔 2 秒）"));
        refresh.setOnAction(e -> manualRefresh());

        Button collapse = new Button();
        collapse.setGraphic(Icons.icon(Icons.ARROW_DOWN, 13));
        collapse.getStyleClass().add("market-collapse");
        collapse.setAccessibleText("收起行情");
        collapse.setTooltip(new Tooltip("收起行情面板"));
        collapse.setOnAction(e -> collapse());

        HBox header = new HBox(4, title, spacer(), updated, refresh, collapse);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("market-header");
        card.getChildren().add(header);
        card.getChildren().add(columnHeader());

        for (RowSpec spec : ROWS) {
            MarketQuoteRow row = new MarketQuoteRow(spec.label(), spec.forex());
            rows.put(spec.key(), row);
            card.getChildren().add(row.node());
        }
        buildBreadthFooter();
        card.getChildren().add(breadthRow);
        card.getStyleClass().add("market-card");
        card.setPrefWidth(380);
        card.setMaxWidth(380);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setAccessibleText("行情：上证指数、恒生科技、富时中国A50、日经225、韩国KOSPI、沪深300、"
                + "创业板指、科创50、中证2000、美元离岸人民币，含成交量、量比与涨跌家数");
        Tooltip.install(card, new Tooltip(
                "数据来源：腾讯财经（新浪兜底）、东方财富（中证2000 / 富时中国A50 / 日经225 / 韩国KOSPI / 涨跌家数）、"
                        + "新浪财经（离岸人民币）\nA 股与港股指数每 " + REFRESH_SECONDS
                        + " 秒刷新，富时中国A50 / 日经225 / 韩国KOSPI、汇率与涨跌家数每 30 秒刷新；"
                        + "源站异常时自动退避，仅供参考"));
    }

    /** 列头：名称 / 量比 / 成交额 / 点位 / 涨跌幅，与各行列宽对齐。 */
    private static HBox columnHeader() {
        Label name = colLabel("名称", false);
        Label ratio = colLabel("量比", true);
        ratio.setMinWidth(32);
        Label turnover = colLabel("成交额", true);
        turnover.setMinWidth(52);
        Label price = colLabel("点位", true);
        price.setMinWidth(74);
        Label change = colLabel("涨跌幅", true);
        change.setMinWidth(72);
        HBox columns = new HBox(6, name, ratio, spacer(), turnover, price, change);
        columns.setAlignment(Pos.CENTER_LEFT);
        columns.getStyleClass().add("market-cols");
        return columns;
    }

    private static Label colLabel(String text, boolean right) {
        Label label = new Label(text);
        label.getStyleClass().add("market-col");
        if (right) {
            label.setAlignment(Pos.CENTER_RIGHT);
        }
        return label;
    }

    private void buildBreadthFooter() {
        Label title = new Label("涨跌家数");
        title.getStyleClass().add("market-name");
        breadthUp.getStyleClass().add("market-breadth-up");
        breadthDown.getStyleClass().add("market-breadth-down");
        breadthFlat.getStyleClass().add("market-breadth-flat");
        HBox counts = new HBox(7, breadthUp, breadthDown, breadthFlat);
        counts.setAlignment(Pos.CENTER_RIGHT);
        breadthRow.getChildren().setAll(title, spacer(), counts);
        breadthRow.setAlignment(Pos.CENTER_LEFT);
        breadthRow.getStyleClass().add("market-breadth-row");
        Tooltip.install(breadthRow, breadthTip);
    }

    private void applyBreadth(MarketBreadth breadth) {
        breadthLoaded = true;
        breadthUp.setText("涨 " + breadth.up());
        breadthDown.setText("跌 " + breadth.down());
        breadthFlat.setText("平 " + breadth.flat());
        breadthTip.setText("沪深北三市涨跌家数 · 更新于 " + LocalTime.now().format(CLOCK));
    }

    private void markBreadthUnavailable() {
        if (!breadthLoaded) {
            breadthUp.setText("涨 —");
            breadthDown.setText("跌 —");
            breadthFlat.setText("平 —");
        }
        breadthTip.setText(breadthLoaded
                ? "涨跌家数刷新失败，显示上一次数据"
                : "涨跌家数暂不可用（数据源限流）");
    }

    private void expand() {
        if (expanded) {
            return;
        }
        expanded = true;
        root.getChildren().setAll(card);
        Motion.enter(card);
        // 展开瞬间补一次，但不能在 FX 线程上做网络请求；已有退避仍生效。
        scheduler.execute(this::tick);
    }

    private void collapse() {
        expanded = false;
        root.getChildren().setAll(toggle);
    }

    /** 显式重试：用户主动操作才允许跳过退避，并做 2 秒节流防连点。 */
    private void manualRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastManualRefresh < 2_000) {
            return;
        }
        lastManualRefresh = now;
        coreNextAt = 0;
        csiNextAt = 0;
        overseasNextAt = 0;
        breadthNextAt = 0;
        fxNextAt = 0;
        scheduler.execute(this::tick);
    }

    /** 每 3 秒一次：腾讯核心指数每次都取；中证2000 / 日韩指数 / 汇率 / 涨跌家数按 30 秒节流并带失败退避。 */
    private void tick() {
        if (closed || !expanded || !fetching.compareAndSet(false, true)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            List<MarketQuote> collected = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            boolean anySuccess = false;

            if (now >= coreNextAt) {
                try {
                    collected.addAll(service.fetchCoreIndices());
                    anySuccess = true;
                    coreFailures = 0;
                    coreNextAt = 0;
                } catch (Exception e) {
                    failed.addAll(CORE_KEYS);
                    coreFailures++;
                    long backoff = CSI_BACKOFF_BASE_MS << Math.min(coreFailures, 6);
                    coreNextAt = now + Math.min(backoff, CSI_MAX_BACKOFF_MS);
                }
            }

            if (now >= csiNextAt) {
                try {
                    collected.addAll(service.fetchCsi2000());
                    anySuccess = true;
                    csiFailures = 0;
                    csiNextAt = 0;
                } catch (Exception e) {
                    failed.add("csi2000");
                    csiFailures++;
                    long backoff = CSI_BACKOFF_BASE_MS << Math.min(csiFailures, 6);
                    csiNextAt = now + Math.min(backoff, CSI_MAX_BACKOFF_MS);
                }
            }

            if (now >= overseasNextAt) {
                try {
                    List<MarketQuote> overseas = service.fetchOverseasIndices();
                    collected.addAll(overseas);
                    anySuccess = true;
                    overseasFailures = 0;
                    overseasNextAt = now + OVERSEAS_INTERVAL_MS;
                    // 单指数缺失（如日经成功、KOSPI 失败）只标记对应行
                    for (String key : OVERSEAS_KEYS) {
                        if (overseas.stream().noneMatch(q -> q.key().equals(key))) {
                            failed.add(key);
                        }
                    }
                } catch (Exception e) {
                    failed.addAll(OVERSEAS_KEYS);
                    overseasFailures++;
                    long backoff = OVERSEAS_INTERVAL_MS << Math.min(overseasFailures, 4);
                    overseasNextAt = now + Math.min(backoff, CSI_MAX_BACKOFF_MS);
                }
            }

            if (now >= fxNextAt) {
                try {
                    collected.add(service.fetchUsdCnh());
                    anySuccess = true;
                    fxFailures = 0;
                    fxNextAt = now + FX_INTERVAL_MS;
                } catch (Exception e) {
                    failed.add(MarketService.USDCNH_KEY);
                    fxFailures++;
                    long backoff = FX_INTERVAL_MS << Math.min(fxFailures, 4);
                    fxNextAt = now + Math.min(backoff, CSI_MAX_BACKOFF_MS);
                }
            }

            MarketBreadth breadth = null;
            boolean breadthFailed = false;
            if (now >= breadthNextAt) {
                try {
                    breadth = service.fetchBreadth();
                    breadthFailures = 0;
                    breadthNextAt = now + BREADTH_INTERVAL_MS;
                } catch (Exception e) {
                    breadthFailed = true;
                    breadthFailures++;
                    long backoff = BREADTH_INTERVAL_MS << Math.min(breadthFailures, 4);
                    breadthNextAt = now + Math.min(backoff, CSI_MAX_BACKOFF_MS);
                }
            }

            List<MarketQuote> snapshot = List.copyOf(collected);
            List<String> failedKeys = List.copyOf(failed);
            boolean success = anySuccess;
            MarketBreadth breadthSnapshot = breadth;
            boolean breadthFailure = breadthFailed;
            Platform.runLater(() -> {
                if (!closed && expanded) {
                    apply(snapshot, failedKeys, success);
                    if (breadthSnapshot != null) {
                        applyBreadth(breadthSnapshot);
                    } else if (breadthFailure) {
                        markBreadthUnavailable();
                    }
                }
            });
        } finally {
            fetching.set(false);
        }
    }

    private void apply(List<MarketQuote> quotes, List<String> failedKeys, boolean anySuccess) {
        long sourceTime = 0;
        for (MarketQuote quote : quotes) {
            sourceTime = Math.max(sourceTime, quote.updatedAt());
            MarketQuoteRow row = rows.get(quote.key());
            if (row != null) {
                row.update(quote);
            }
        }
        for (String key : failedKeys) {
            MarketQuoteRow row = rows.get(key);
            if (row != null) {
                row.markStale();
            }
        }
        updated.getStyleClass().remove("market-delayed");
        if (anySuccess) {
            long now = System.currentTimeMillis();
            long display = sourceTime > 0 ? sourceTime : now;
            updated.setText(MarketQuoteRow.timeText(display));
            if (sourceTime > 0 && now - sourceTime > Duration.ofMinutes(5).toMillis()) {
                // 休市/源延迟：明确标出来，避免把旧行情当成实时数据
                updated.getStyleClass().add("market-delayed");
            }
            updated.setTooltip(new Tooltip(sourceTime > 0
                    ? "行情源时间；本地抓取于 " + LocalTime.now().format(CLOCK)
                    : "源未提供行情时间，显示本地抓取时间"));
            card.getStyleClass().remove("market-stale");
        } else {
            updated.setText("离线");
            updated.setTooltip(new Tooltip("数据源暂不可用，保留上一次行情"));
            if (!card.getStyleClass().contains("market-stale")) {
                card.getStyleClass().add("market-stale");
            }
        }
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
