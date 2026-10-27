package com.dp.agent.core.tool;

import com.dp.agent.config.ToolApprovalPolicy;
import com.dp.agent.core.AgentTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 3：工具守卫——白名单、审批、异常转结果、超时（AI-KNOWLEDGE.md 第 3 章配套）。
 * 运行：mvn test -Dtest=Lab03ToolGuardLabTest -Dsurefire.useFile=false
 */
class Lab03ToolGuardLabTest {

    @TempDir
    Path tempDir;

    @Test
    void exp1_annotationScanRegistersTools() {
        System.out.println("── 实验 3.1 @Tool 注解自动上架：一个方法就是一件商品 ──");
        ToolRegistry registry = ToolRegistry.scan(new AgentTools());
        System.out.println("扫描到 " + registry.size() + " 个工具: " + registry.names());
        for (String name : new String[]{"calculator", "currentTime"}) {
            System.out.println("  " + name + " → 白名单内(免审批) = " + registry.isSafe(name));
        }
        assertEquals(4, registry.size());
        assertTrue(registry.isSafe("calculator"));
    }

    @Test
    void exp2_safeToolSkipsApprovalGate() {
        System.out.println("── 实验 3.2 白名单 = 常客卡：安检口直接放行 ──");
        CountingGate gate = new CountingGate();
        ToolAuditLog audit = new ToolAuditLog(tempDir);
        GuardedToolExecutor executor = new GuardedToolExecutor("calculator",
                (request, memoryId) -> "1024.0", true,
                runtime(ToolApprovalPolicy.AUTO, gate, audit));

        String result = executor.execute(request(), null);

        System.out.println("白名单工具执行结果: " + result);
        System.out.println("弹窗次数: " + gate.calls + "（AUTO 模式下白名单工具根本不来打扰用户）");
        assertEquals(0, gate.calls);
        assertEquals("1024.0", result);
    }

    @Test
    void exp3_toolFailureBecomesRecoverableResult() {
        System.out.println("── 实验 3.3 后厨着火不上桌：工具异常变成「错误菜单」喂回模型 ──");
        ToolAuditLog audit = new ToolAuditLog(tempDir);
        GuardedToolExecutor executor = new GuardedToolExecutor("calculator",
                (request, memoryId) -> {
                    throw new IllegalStateException("表达式解析失败: 意外的符号 '#'");
                }, true,
                runtime(ToolApprovalPolicy.AUTO, new CountingGate(), audit));

        ToolExecutionResult result = executor.executeWithContext(request(), context());

        System.out.println("isError = " + result.isError());
        System.out.println("回给模型的文本: " + result.resultText());
        System.out.println("→ 模型看到失败原因，可以换个参数重试，而不是整轮对话崩掉");
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("失败"));
    }

    @Test
    void exp4_slowToolIsTimedOut() {
        System.out.println("── 实验 3.4 卡死的工具会被计时器掐掉 ──");
        ToolAuditLog audit = new ToolAuditLog(tempDir);
        GuardedToolExecutor executor = new GuardedToolExecutor("slowTool",
                (request, memoryId) -> {
                    try {
                        Thread.sleep(3000);   // 模拟卡死：外部服务无响应
                        return "太迟了";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ToolCancelledException();
                    }
                }, true,
                runtime(ToolApprovalPolicy.AUTO, new CountingGate(), audit), 1);

        long start = System.currentTimeMillis();
        ToolExecutionResult result = executor.executeWithContext(request(), context());
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("工具要睡 3 秒，超时上限 1 秒 → 实际等待 " + elapsed + "ms 后拿到:");
        System.out.println("  isError = " + result.isError() + ", 文本 = " + result.resultText());
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("超时"));
        assertTrue(elapsed < 2500, "不应等满工具的 3 秒");
    }

    private static ToolRuntime runtime(ToolApprovalPolicy policy, ToolApprovalGate gate,
                                       ToolAuditLog audit) {
        return new ToolRuntime("lab-session", policy, gate, audit, event -> {
        });
    }

    private static ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("call-lab").name("calculator")
                .arguments("{\"expression\":\"2^10\"}").build();
    }

    private static InvocationContext context() {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName("lab").methodName("execute")
                .methodArguments(List.of())
                .chatMemoryId("memory")
                .invocationParameters(InvocationParameters.from(Map.of()))
                .timestampNow().build();
    }

    /** 审批门 stub：记录被打扰次数。 */
    private static final class CountingGate implements ToolApprovalGate {
        int calls;

        @Override
        public Decision request(ApprovalRequest request) {
            calls++;
            return Decision.APPROVED;
        }
    }
}
