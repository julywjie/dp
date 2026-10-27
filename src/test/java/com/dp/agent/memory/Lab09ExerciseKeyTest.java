package com.dp.agent.memory;

import com.dp.agent.chat.Conversation;
import com.dp.agent.config.ToolApprovalPolicy;
import com.dp.agent.core.context.ContextPlanner;
import com.dp.agent.core.context.TokenEstimator;
import com.dp.agent.core.tool.GuardedToolExecutor;
import com.dp.agent.core.tool.ToolApprovalGate;
import com.dp.agent.core.tool.ToolAuditLog;
import com.dp.agent.core.tool.ToolRegistry;
import com.dp.agent.core.tool.ToolRuntime;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 实验室 9：AI-KNOWLEDGE.md 跟练版练习题的答案验证。
 * 每个练习的「参考答案」输出都由本类真实运行产出，不靠口算。
 * 运行：mvn test -Dtest=Lab09* -Dsurefire.useFile=false
 */
class Lab09ExerciseKeyTest {

    @TempDir
    Path tempDir;

    @Test
    void ex1_1_mixedMessageCarriesTextAndRequest() {
        System.out.println("── 练习 1.1 蓝便签能同时说话+递单子吗？──");
        AiMessage both = AiMessage.from("我需要先算一下", List.of(
                ToolExecutionRequest.builder().id("c1").name("calculator")
                        .arguments("{\"expression\":\"2^10\"}").build()));
        System.out.println("text() = " + both.text());
        System.out.println("toolExecutionRequests().size() = " + both.toolExecutionRequests().size());
    }

    @Test
    void ex4_1_mixedLanguageTokenEstimate() {
        System.out.println("── 练习 4.1 混合文本估多少 token？──");
        String mixed = "你好，世界！hello world";
        System.out.println("estimate(\"" + mixed + "\") = " + TokenEstimator.estimate(mixed));
    }

    @Test
    void ex4_2_budgetArithmetic() {
        System.out.println("── 练习 4.2 16K 窗口 / 2048 输出 / 3 工具 → 预算？──");
        var in = new ContextPlanner.Input(List.of(), 0, "", "", "", 16 * 1024, 2048, 3);
        System.out.println("budget = " + ContextPlanner.budget(in));
    }

    @Test
    void ex5_2and5_3_bigramAndJaccard() {
        System.out.println("── 练习 5.2 tokens(\"人工智能\") 与 练习 5.3 喜欢 vs 偏爱 ──");
        System.out.println("tokens(\"人工智能\") = "
                + new java.util.TreeSet<>(MemoryRetriever.tokens("人工智能")));

        Set<String> a = MemoryRetriever.tokens("用户喜欢深色主题");
        Set<String> b = MemoryRetriever.tokens("用户偏爱深色主题");
        double inter = 0;
        for (String t : b) {
            if (a.contains(t)) {
                inter++;
            }
        }
        System.out.printf("「用户喜欢深色主题」vs「用户偏爱深色主题」: 交集=%.0f 并集=%.0f Jaccard=%.2f%n",
                inter, a.size() + b.size() - inter, inter / (a.size() + b.size() - inter));
        System.out.println("（三级判重：不相等、不包含、Jaccard<0.8 → 不判重，两条并存）");
    }

    @Test
    void ex3_2_newToolNeedsApproval() {
        System.out.println("── 练习 3.2 新加一个 @Tool，默认要审批吗？──");
        Object demoTools = new Object() {
            @Tool("回显一段文本，仅用于演示")
            public String echo(String text) {
                return text;
            }
        };
        ToolRegistry registry = ToolRegistry.scan(demoTools);
        System.out.println("扫描到 " + registry.size() + " 个工具");
        System.out.println("isSafe(\"echo\") = " + registry.isSafe("echo")
                + "（不在白名单 → 默认弹窗确认）");
    }

    @Test
    void ex3_3_unknownToolAlwaysAsks() {
        System.out.println("── 练习 3.3 非白名单工具，AUTO 模式弹几次窗？──");
        CountingGate gate = new CountingGate();
        ToolAuditLog audit = new ToolAuditLog(tempDir);
        GuardedToolExecutor executor = new GuardedToolExecutor("echo",
                (request, memoryId) -> "ok",
                false,                              // ★ 不在白名单
                new ToolRuntime("s", ToolApprovalPolicy.AUTO, gate, audit, e -> {
                }));
        executor.execute(ToolExecutionRequest.builder()
                .id("c1").name("echo").arguments("{\"text\":\"hi\"}").build(), null);
        System.out.println("弹窗次数 = " + gate.calls + "，审计 decision = APPROVED（非 AUTO_APPROVED）");
    }

    @Test
    void ex4_5_doubleRollbackIsNoop() {
        System.out.println("── 练习 4.5 连续 rollback 两次会怎样？──");
        Conversation conversation = new Conversation();
        conversation.addUser("问题");
        conversation.rollbackLastUser();
        System.out.println("第一次后条数 = " + conversation.historySnapshot().size());
        conversation.rollbackLastUser();
        System.out.println("第二次后条数 = " + conversation.historySnapshot().size() + "（末条已非 UserMessage → no-op）");
    }

    private static final class CountingGate implements ToolApprovalGate {
        int calls;

        @Override
        public Decision request(ApprovalRequest request) {
            calls++;
            return Decision.APPROVED;
        }
    }
}
