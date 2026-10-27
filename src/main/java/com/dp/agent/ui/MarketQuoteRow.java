package com.dp.agent.ui;

import com.dp.agent.market.MarketQuote;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 行情卡单行：名称 / 点位 / 涨跌幅；成交量、量比等细节放进 tooltip。
 * 从 {@link MarketTicker} 拆出的独立行渲染单元，纯 UI 无网络与状态。
 */
final class MarketQuoteRow {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final boolean forex;
    private final Label name = new Label();
    private final Label ratio = new Label("—");
    private final Label turnover = new Label("—");
    private final Label price = new Label("--");
    private final Label change = new Label();
    private final HBox row = new HBox(6, name, ratio, spacer(), turnover, price, change);
    private final Tooltip tooltip = new Tooltip("等待行情数据…");

    MarketQuoteRow(String displayName, boolean forex) {
        this.forex = forex;
        name.setText(displayName);
        name.setMinWidth(Region.USE_PREF_SIZE);
        name.getStyleClass().add("market-name");
        ratio.getStyleClass().add("market-ratio");
        turnover.getStyleClass().add("market-turnover");
        price.getStyleClass().add("market-price");
        change.getStyleClass().add("market-change");
        ratio.setMinWidth(32);
        ratio.setAlignment(Pos.CENTER_RIGHT);
        turnover.setMinWidth(52);
        turnover.setAlignment(Pos.CENTER_RIGHT);
        price.setMinWidth(74);
        price.setAlignment(Pos.CENTER_RIGHT);
        change.setMinWidth(72);
        change.setAlignment(Pos.CENTER_RIGHT);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("market-row");
        Tooltip.install(row, tooltip);
    }

    HBox node() {
        return row;
    }

    void update(MarketQuote quote) {
        row.getStyleClass().removeAll("market-up", "market-down", "market-flat",
                "market-stale-row");
        if (quote == null || !quote.hasValue()) {
            price.setText("--");
            change.setText("");
            tooltip.setText("数据源未返回有效值");
            row.getStyleClass().add("market-flat");
            return;
        }
        price.setText(String.format(Locale.CHINA,
                forex ? "%,.4f" : "%,.2f", quote.price()));
        ratio.setText(quote.volumeRatio() > 0
                ? String.format(Locale.CHINA, "%.2f", quote.volumeRatio()) : "—");
        turnover.setText(quote.amount() > 0 ? formatAmount(quote.amount()) : "—");
        double percent = quote.changePercent();
        String arrow = percent > 0 ? "▲ " : percent < 0 ? "▼ " : "— ";
        change.setText(String.format(Locale.CHINA,
                arrow + (percent > 0 ? "+%.2f%%" : "%.2f%%"), percent));
        if (percent > 0) {
            row.getStyleClass().add("market-up");
        } else if (percent < 0) {
            row.getStyleClass().add("market-down");
        } else {
            row.getStyleClass().add("market-flat");
        }
        String time = quote.updatedAt() > 0
                ? "数据时间 " + timeText(quote.updatedAt())
                : "数据源未提供时间";
        String detail = forex
                ? "美元兑离岸人民币即期汇率，仅供参考"
                : metaText(quote) + (quote.amount() > 0
                        ? "\n成交额单位：A 股为元，港股为港元" : "");
        tooltip.setText(time + "\n" + detail);
    }

    void markStale() {
        row.getStyleClass().add("market-stale-row");
        tooltip.setText("本次刷新失败，显示上一次数据");
    }

    static String timeText(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                .toLocalTime().format(CLOCK);
    }

    private static String metaText(MarketQuote quote) {
        StringBuilder text = new StringBuilder("成交 ");
        boolean hasVolume = quote.volume() > 0;
        boolean hasAmount = quote.amount() > 0;
        if (hasVolume) {
            text.append(formatVolume(quote.volume()));
        }
        if (hasVolume && hasAmount) {
            text.append(" / ");
        }
        if (hasAmount) {
            text.append(formatAmount(quote.amount()));
        }
        if (!hasVolume && !hasAmount) {
            text.append("—");
        }
        text.append(" · 量比 ");
        text.append(quote.volumeRatio() > 0
                ? String.format(Locale.CHINA, "%.2f", quote.volumeRatio())
                : "—");
        return text.toString();
    }

    private static String formatVolume(double shares) {
        Locale locale = Locale.CHINA;
        if (shares >= 100_000_000) {
            return String.format(locale, "%.2f亿股", shares / 100_000_000);
        }
        if (shares >= 10_000) {
            return String.format(locale, "%.0f万股", shares / 10_000);
        }
        return String.format(locale, "%.0f股", shares);
    }

    private static String formatAmount(double amount) {
        Locale locale = Locale.CHINA;
        if (amount >= 100_000_000) {
            return String.format(locale, "%.0f亿", amount / 100_000_000);
        }
        if (amount >= 10_000) {
            return String.format(locale, "%.0f万", amount / 10_000);
        }
        return String.format(locale, "%.0f", amount);
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
