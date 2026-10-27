package com.dp.agent.core.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * 上下文预算的纯决策逻辑：判断是否需要压缩、切点在哪、保留多少。
 * 不做 IO、不调用模型，便于单测。
 */
public final class ContextPlanner {

    public enum Mode {
        /** 无需压缩。 */
        NONE,
        /** 摘要 [covered, cut) 的较早消息。 */
        SUMMARIZE,
        /** 直接丢弃 [covered, cut) 的较早消息。 */
        TRUNCATE
    }

    public record Input(List<ChatMessage> history, int covered, String summary,
                        String systemPrompt, String userText,
                        int contextWindow, int maxOutputTokens, int toolCount) {
    }

    public record Plan(Mode mode, int cut, int budget, int estimatedBefore,
                       int estimatedAfter, boolean userOverBudget) {
    }

    static final double TRIGGER_RATIO = 0.90;
    static final double KEEP_RATIO = 0.50;
    static final int SUMMARY_RESERVE = 800;
    static final int BASE_OVERHEAD = 512;

    private ContextPlanner() {
    }

    public static int budget(Input input) {
        int toolOverhead = Math.min(1000, Math.max(0, input.toolCount()) * 150);
        return Math.max(256, input.contextWindow() - input.maxOutputTokens()
                - BASE_OVERHEAD - toolOverhead);
    }

    public static Plan plan(Input input) {
        int budget = budget(input);
        List<ChatMessage> history = input.history();
        int covered = clamp(input.covered(), 0, history.size());
        String summary = input.summary() == null ? "" : input.summary();

        int estimated = TokenEstimator.estimate(input.systemPrompt())
                + TokenEstimator.estimate(summary)
                + TokenEstimator.estimateRange(history, covered, history.size())
                + TokenEstimator.estimate(input.userText());

        if (estimated <= budget * TRIGGER_RATIO) {
            return new Plan(Mode.NONE, covered, budget, estimated, estimated, false);
        }

        int minimum = TokenEstimator.estimate(input.systemPrompt())
                + TokenEstimator.estimate(input.userText());
        if (minimum > budget) {
            return new Plan(Mode.TRUNCATE, history.size(), budget, estimated, minimum, true);
        }

        int lastTurn = lastUserIndex(history);
        int cut = chooseCut(input, covered, (int) (budget * KEEP_RATIO));
        cut = snapToUserBoundary(history, cut);
        cut = Math.min(cut, lastTurn);
        cut = Math.max(cut, covered);

        Mode mode = cut > covered ? Mode.SUMMARIZE : Mode.TRUNCATE;

        // 摘要输入本身不能超过模型的窗口
        int summaryInputLimit = Math.max(256, input.contextWindow() - 1024);
        while (mode == Mode.SUMMARIZE
                && summaryTokens(input, covered, cut) > summaryInputLimit) {
            int previous = previousUserBoundary(history, cut - 1, covered);
            if (previous <= covered) {
                mode = Mode.TRUNCATE;
                break;
            }
            cut = previous;
        }

        // 压缩后仍超预算：继续向近期收缩（至少保留最后一轮）
        while (estimateAfter(input, cut) > budget && cut < lastTurn) {
            int next = nextUserBoundary(history, cut + 1);
            if (next <= cut) {
                break;
            }
            cut = next;
        }

        int after = estimateAfter(input, cut) + (mode == Mode.SUMMARIZE ? SUMMARY_RESERVE : 0);
        return new Plan(mode, cut, budget, estimated, after, false);
    }

    private static int chooseCut(Input input, int covered, int keepBudget) {
        int cut = input.history().size();
        int total = TokenEstimator.estimate(input.systemPrompt())
                + TokenEstimator.estimate(input.userText());
        List<ChatMessage> history = input.history();
        for (int i = history.size() - 1; i >= covered; i--) {
            int next = total + TokenEstimator.estimateMessage(history.get(i));
            if (next > keepBudget) {
                break;
            }
            total = next;
            cut = i;
        }
        return cut;
    }

    private static int estimateAfter(Input input, int cut) {
        return TokenEstimator.estimate(input.systemPrompt())
                + TokenEstimator.estimateRange(input.history(), cut, input.history().size())
                + TokenEstimator.estimate(input.userText());
    }

    private static int summaryTokens(Input input, int covered, int cut) {
        return TokenEstimator.estimate(input.summary())
                + TokenEstimator.estimateRange(input.history(), covered, cut);
    }

    private static int snapToUserBoundary(List<ChatMessage> history, int cut) {
        int index = clamp(cut, 0, history.size());
        while (index < history.size() && !(history.get(index) instanceof UserMessage)) {
            index++;
        }
        return index;
    }

    private static int lastUserIndex(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return history.size();
    }

    private static int previousUserBoundary(List<ChatMessage> history, int from, int floor) {
        for (int i = Math.min(from, history.size() - 1); i >= floor; i--) {
            if (history.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return floor;
    }

    private static int nextUserBoundary(List<ChatMessage> history, int from) {
        for (int i = Math.max(0, from); i < history.size(); i++) {
            if (history.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return history.size();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
