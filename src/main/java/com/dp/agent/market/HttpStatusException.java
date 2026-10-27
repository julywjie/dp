package com.dp.agent.market;

import java.io.IOException;
import java.net.URI;

/**
 * 行情源 HTTP 非 200 响应。
 * 保留状态码，便于区分「可换节点的 5xx」与「不该重试的 403/429」。
 */
class HttpStatusException extends IOException {

    private final int statusCode;

    HttpStatusException(int statusCode) {
        super("HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    int statusCode() {
        return statusCode;
    }

    /** 403/429 视为触发风控：继续轮询只会更糟，应立即换源或放弃。 */
    static boolean isRateLimited(IOException e) {
        return e instanceof HttpStatusException status
                && (status.statusCode == 403 || status.statusCode == 429);
    }

    static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }
}