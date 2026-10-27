package com.dp.agent.market;

/**
 * 单个指数的最新报价。
 *
 * price<=0 表示数据源本次未返回有效值；
 * volume 统一为「股」，amount 为「元/港元」，volumeRatio 为量比；
 * updatedAt 为源站行情时间（epoch millis），0 表示源未提供时间。
 */
public record MarketQuote(String key, String name, double price,
                          double change, double changePercent,
                          double volume, double amount, double volumeRatio,
                          long updatedAt) {

    public MarketQuote(String key, String name, double price,
                       double change, double changePercent, long updatedAt) {
        this(key, name, price, change, changePercent, 0, 0, 0, updatedAt);
    }

    public MarketQuote(String key, String name, double price,
                       double change, double changePercent) {
        this(key, name, price, change, changePercent, 0, 0, 0, 0);
    }

    public boolean hasValue() {
        return price > 0;
    }
}
