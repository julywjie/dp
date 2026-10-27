package com.dp.agent.core.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * 轻量 token 估算，不引入分词器依赖：
 *   ASCII 约 4 字符/token，CJK 约 1 字/token，其他非 ASCII 约 2 字符/token。
 * 误差由上下文预算中的固定余量覆盖，用于「是否需要压缩」的粗判断，不用于计费。
 */
public final class TokenEstimator {

    private static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint < 128) {
                tokens += 0.25;
            } else if (isCjk(codePoint)) {
                tokens += 1.0;
            } else {
                tokens += 0.5;
            }
            i += Character.charCount(codePoint);
        }
        return (int) Math.ceil(tokens);
    }

    public static int estimateMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        String text = "";
        if (message instanceof UserMessage user) {
            text = user.singleText();
        } else if (message instanceof AiMessage ai) {
            text = ai.text();
        } else if (message instanceof SystemMessage system) {
            text = system.text();
        } else if (message instanceof ToolExecutionResultMessage tool) {
            text = tool.text();
        }
        return estimate(text) + MESSAGE_OVERHEAD;
    }

    public static int estimateRange(List<ChatMessage> messages, int from, int to) {
        int total = 0;
        int start = Math.max(0, from);
        int end = Math.min(to, messages.size());
        for (int i = start; i < end; i++) {
            total += estimateMessage(messages.get(i));
        }
        return total;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
