package com.dp.agent.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** 用户可见错误消息的统一收敛：解包、去空白、限制长度。 */
public final class Errors {

    private Errors() {
    }

    public static String userMessage(Throwable error) {
        return userMessage(error, "操作失败，请稍后重试。", 500);
    }

    public static String userMessage(Throwable error, String fallback, int maxChars) {
        Throwable cause = unwrap(error);
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause == null ? fallback : cause.getClass().getSimpleName();
        }
        message = message.trim().replaceAll("\\s+", " ");
        if (message.isBlank()) {
            message = fallback;
        }
        return message.length() <= maxChars ? message : message.substring(0, maxChars) + "…";
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
