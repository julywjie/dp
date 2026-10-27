package com.dp.agent.core.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPlannerTest {

    @Test
    void smallContextNeedsNoCompaction() {
        ContextPlanner.Plan plan = ContextPlanner.plan(input(history(2, 20), "", "hi"));
        assertEquals(ContextPlanner.Mode.NONE, plan.mode());
        assertFalse(plan.userOverBudget());
    }

    @Test
    void oversizedContextSummarizesOnUserBoundaryAndKeepsLastTurn() {
        List<ChatMessage> history = history(20, 400);
        ContextPlanner.Plan plan = ContextPlanner.plan(input(history, "", "继续"));

        assertTrue(plan.mode() == ContextPlanner.Mode.SUMMARIZE
                || plan.mode() == ContextPlanner.Mode.TRUNCATE);
        assertTrue(plan.cut() < history.size());
        assertEquals("u0 ", ((UserMessage) history.get(0)).singleText().substring(0, 3));
        if (plan.cut() < history.size()) {
            assertTrue(history.get(plan.cut()) instanceof UserMessage,
                    "切点必须落在用户消息上，保证保留完整轮次");
        }
        int lastUser = history.size() - 2;
        assertTrue(plan.cut() <= lastUser, "必须保留最后一轮");
    }

    @Test
    void rejectsWhenCurrentInputAloneExceedsBudget() {
        ContextPlanner.Plan plan = ContextPlanner.plan(input(history(1, 10), "",
                "你".repeat(5000)));
        assertTrue(plan.userOverBudget());
    }

    @Test
    void keepsAtLeastOneTurnWhenHistoryIsHuge() {
        List<ChatMessage> history = history(60, 500);
        ContextPlanner.Plan plan = ContextPlanner.plan(input(history, "", "继续"));
        assertTrue(plan.cut() <= history.size() - 2);
    }

    private static ContextPlanner.Input input(List<ChatMessage> history, String summary, String user) {
        return new ContextPlanner.Input(history, 0, summary,
                "你是助手。", user, 4000, 512, 4);
    }

    private static List<ChatMessage> history(int pairs, int chars) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            messages.add(UserMessage.from("u" + i + " " + "x".repeat(chars)));
            messages.add(AiMessage.from("a" + i + " " + "y".repeat(chars)));
        }
        return messages;
    }
}
