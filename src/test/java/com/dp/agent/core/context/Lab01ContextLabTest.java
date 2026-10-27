package com.dp.agent.core.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 1：Token 与上下文预算（AI-KNOWLEDGE.md 第 4 章配套）。
 * 运行：mvn test -Dtest=Lab01ContextLabTest -Dsurefire.useFile=false
 */
class Lab01ContextLabTest {

    @Test
    void exp1_tokenCountsDifferByLanguage() {
        System.out.println("── 实验 1.1 同样长度的话，token 花销差 4 倍 ──");
        String english = "The quick brown fox jumps over the lazy dog";   // 44 字符
        String chinese = "敏捷的棕色狐狸跳过了懒惰的狗";                     // 12 个汉字
        System.out.println("英文 44 字符 ≈ " + TokenEstimator.estimate(english) + " tokens（4 字符 ≈ 1 token）");
        System.out.println("中文 12 个汉字 ≈ " + TokenEstimator.estimate(chinese) + " tokens（1 汉字 ≈ 1 token）");
        System.out.println("每条消息还要 +4 结构开销："
                + TokenEstimator.estimateMessage(new UserMessage(english)) + "（44 字符英文消息）");
        assertTrue(TokenEstimator.estimate(chinese) >= 12);
    }

    @Test
    void exp2_toolsEatYourBudget() {
        System.out.println("── 实验 1.2 工具不是免费的：注册越多工具，留给对话的空间越小 ──");
        int window = 32 * 1024;
        for (int tools : new int[]{0, 4, 6, 7, 20}) {
            ContextPlanner.Input in = new ContextPlanner.Input(
                    List.of(), 0, "", "", "", window, 4096, tools);
            System.out.printf("窗口 32K，输出预留 4096，注册 %2d 个工具 → 可用输入预算 %d tokens%n",
                    tools, ContextPlanner.budget(in));
        }
        System.out.println("→ 每个工具的 JSON Schema 约按 150 tokens 估算，但开销封顶 1000：");
        System.out.println("   7 个工具本应扣 1050，实际扣 1000（6 个=900 未到顶，7 与 20 相同）");
        ContextPlanner.Input seven = new ContextPlanner.Input(
                List.of(), 0, "", "", "", window, 4096, 7);
        assertEquals(32 * 1024 - 4096 - 512 - Math.min(1000, 7 * 150),
                ContextPlanner.budget(seven));
    }

    @Test
    void exp3_plannerThreeModes() {
        System.out.println("── 实验 1.3 压缩决策三态：不压 / 摘要 / 截断 ──");

        // 10 轮短对话，远小于预算 → NONE
        List<dev.langchain4j.data.message.ChatMessage> history = chatRounds(10);
        ContextPlanner.Input small = new ContextPlanner.Input(
                history, 0, "", "系统提示", "新问题", 32 * 1024, 4096, 4);
        ContextPlanner.Plan none = ContextPlanner.plan(small);
        System.out.println("10 轮短对话 → " + none.mode() + "（估算 " + none.estimatedBefore()
                + " tokens，未到预算 " + none.budget() + " 的 90% 红线）");

        // 同样的对话，把窗口压到 1K → 超过 90% 红线 → SUMMARIZE，切点对齐用户消息
        ContextPlanner.Input tight = new ContextPlanner.Input(
                history, 0, "", "系统提示", "新问题", 1024, 256, 0);
        ContextPlanner.Plan summarize = ContextPlanner.plan(tight);
        System.out.println("窗口压到 1K → " + summarize.mode() + "（切点在第 "
                + summarize.cut() + " 条消息，之前的历史拿去生成摘要）");
        assertTrue(summarize.cut() < history.size());
        assertTrue(summarize.mode() == ContextPlanner.Mode.SUMMARIZE
                || summarize.mode() == ContextPlanner.Mode.TRUNCATE);

        // 用户单条输入就超预算 → 无从摘要，只能 TRUNCATE 兜底
        ContextPlanner.Input huge = new ContextPlanner.Input(
                List.of(), 0, "", "系统提示", "长".repeat(3000), 1024, 256, 0);
        ContextPlanner.Plan truncate = ContextPlanner.plan(huge);
        System.out.println("用户单条输入 3000 字、窗口 1K → " + truncate.mode()
                + "（userOverBudget=" + truncate.userOverBudget() + "，摘要救不了，只能截断）");
        assertEquals(ContextPlanner.Mode.TRUNCATE, truncate.mode());
        assertTrue(truncate.userOverBudget());
    }

    private static List<dev.langchain4j.data.message.ChatMessage> chatRounds(int rounds) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= rounds; i++) {
            messages.add(new UserMessage("第 " + i + " 个问题：介绍一下 Java 并发工具类"));
            messages.add(new AiMessage("第 " + i + " 个回答：常用的有 ExecutorService、"
                    + "CountDownLatch、Atomic 系列……"));
        }
        return messages;
    }
}
