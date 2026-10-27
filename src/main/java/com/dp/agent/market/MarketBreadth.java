package com.dp.agent.market;

/** 沪深北三市涨跌家数。 */
public record MarketBreadth(int up, int down, int flat) {

    /** 合理性校验：字段映射变化或源站返回异常时，宁可显示“—”也不展示错误数字。 */
    public boolean isValid() {
        int total = up + down + flat;
        return up >= 0 && down >= 0 && flat >= 0 && total > 100 && total < 20_000;
    }
}
