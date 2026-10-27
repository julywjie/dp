package com.dp.agent.core.tool;

import com.dp.agent.config.ToolApprovalPolicy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedToolExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void safeToolInAutoModeSkipsApproval() {
        FakeExecutor delegate = new FakeExecutor();
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.APPROVED);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        String result = executor(delegate, true, ToolApprovalPolicy.AUTO, gate, audit)
                .execute(request(), null);

        assertEquals("ok", result);
        assertEquals(0, gate.calls);
        assertEquals(1, delegate.calls);
        String line = auditLines(audit).get(0);
        assertTrue(line.contains("\"decision\":\"AUTO_APPROVED\""));
        assertTrue(line.contains("\"success\":true"));
    }

    @Test
    void unknownToolAsksAndExecutesWhenApproved() {
        FakeExecutor delegate = new FakeExecutor();
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.APPROVED);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        executor(delegate, false, ToolApprovalPolicy.AUTO, gate, audit).execute(request(), null);

        assertEquals(1, gate.calls);
        assertEquals(1, delegate.calls);
        assertTrue(auditLines(audit).get(0).contains("\"decision\":\"APPROVED\""));
    }

    @Test
    void alwaysPolicyAsksForSafeToolsToo() {
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.APPROVED);
        executor(new FakeExecutor(), true, ToolApprovalPolicy.ALWAYS, gate, new ToolAuditLog(tempDir))
                .execute(request(), null);
        assertEquals(1, gate.calls);
    }

    @Test
    void denialSkipsExecutionAndIsAudited() {
        FakeExecutor delegate = new FakeExecutor();
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.DENIED);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        String result = executor(delegate, false, ToolApprovalPolicy.AUTO, gate, audit)
                .execute(request(), null);

        assertEquals("工具调用已被用户拒绝。", result);
        assertEquals(0, delegate.calls);
        String line = auditLines(audit).get(0);
        assertTrue(line.contains("\"decision\":\"DENIED\""));
        assertTrue(line.contains("\"success\":false"));
    }

    @Test
    void timeoutIsTreatedAsDenial() {
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.TIMEOUT);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        String result = executor(new FakeExecutor(), false, ToolApprovalPolicy.AUTO, gate, audit)
                .execute(request(), null);

        assertTrue(result.contains("超时"));
        assertTrue(auditLines(audit).get(0).contains("\"decision\":\"TIMEOUT\""));
    }

    @Test
    void cancelThrowsAndIsAudited() {
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.CANCELLED);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        assertThrows(ToolCancelledException.class,
                () -> executor(new FakeExecutor(), false, ToolApprovalPolicy.AUTO, gate, audit)
                        .execute(request(), null));
        assertTrue(auditLines(audit).get(0).contains("\"decision\":\"CANCELLED\""));
    }

    @Test
    void interruptFromGateBecomesCancellation() {
        FakeGate gate = new FakeGate(null);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        assertThrows(ToolCancelledException.class,
                () -> executor(new FakeExecutor(), false, ToolApprovalPolicy.AUTO, gate, audit)
                        .execute(request(), null));
        assertTrue(Thread.interrupted(), "中断标记应被保留");
    }

    @Test
    void executeWithContextIsGuardedAndAudited() {
        FakeExecutor delegate = new FakeExecutor();
        FakeGate gate = new FakeGate(ToolApprovalGate.Decision.APPROVED);
        ToolAuditLog audit = new ToolAuditLog(tempDir);

        ToolExecutionResult result = executor(delegate, false, ToolApprovalPolicy.AUTO, gate, audit)
                .executeWithContext(request(), context());

        assertEquals("ok", result.resultText());
        assertEquals(1, gate.calls);
        assertTrue(auditLines(audit).get(0).contains("\"decision\":\"APPROVED\""));
    }

    @Test
    void toolFailureBecomesErrorResultInsteadOfKillingRun() {
        ToolAuditLog audit = new ToolAuditLog(tempDir);
        ToolExecutionResult result = executor(new ThrowingExecutor(), false,
                ToolApprovalPolicy.AUTO,
                new FakeGate(ToolApprovalGate.Decision.APPROVED), audit)
                .executeWithContext(request(), context());

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("失败"));
        assertTrue(auditLines(audit).get(0).contains("\"success\":false"));
    }

    @Test
    void toolFailureOnStringApiReturnsMessage() {
        String result = executor(new ThrowingExecutor(), false,
                ToolApprovalPolicy.AUTO,
                new FakeGate(ToolApprovalGate.Decision.APPROVED),
                new ToolAuditLog(tempDir)).execute(request(), null);

        assertTrue(result.contains("失败"));
    }

    @Test
    void toolTimeoutReturnsErrorResult() {
        ToolRuntime runtime = runtime(ToolApprovalPolicy.AUTO,
                new FakeGate(ToolApprovalGate.Decision.APPROVED),
                new ToolAuditLog(tempDir));
        GuardedToolExecutor executor = new GuardedToolExecutor(
                "calculator", new SlowExecutor(), true, runtime, 1);

        ToolExecutionResult result = executor.executeWithContext(request(), context());

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("超时"));
    }

    @Test
    void auditFailureDoesNotBlockExecution() throws Exception {
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        ToolAuditLog broken = new ToolAuditLog(blocked);

        String result = executor(new FakeExecutor(), true, ToolApprovalPolicy.AUTO,
                new FakeGate(ToolApprovalGate.Decision.APPROVED), broken).execute(request(), null);

        assertEquals("ok", result);
        assertTrue(broken.lastError() != null, "审计失败应被记录，且不阻断执行");
    }

    private GuardedToolExecutor executor(ToolExecutor delegate, boolean safe,
                                         ToolApprovalPolicy policy,
                                         ToolApprovalGate gate, ToolAuditLog audit) {
        return new GuardedToolExecutor("calculator", delegate, safe,
                runtime(policy, gate, audit));
    }

    private static ToolRuntime runtime(ToolApprovalPolicy policy,
                                       ToolApprovalGate gate, ToolAuditLog audit) {
        return new ToolRuntime("session-1", policy, gate, audit, event -> {
        });
    }

    private static ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("calculator")
                .arguments("{\"expression\":\"1+1\"}")
                .build();
    }

    private static InvocationContext context() {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName("test")
                .methodName("execute")
                .methodArguments(List.of())
                .chatMemoryId("memory")
                .invocationParameters(InvocationParameters.from(Map.of()))
                .timestampNow()
                .build();
    }

    private static List<String> auditLines(ToolAuditLog audit) {
        try {
            return Files.readAllLines(audit.file());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static final class FakeExecutor implements ToolExecutor {
        int calls;

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            calls++;
            return "ok";
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                                                      InvocationContext context) {
            calls++;
            return ToolExecutionResult.builder().resultText("ok").build();
        }
    }

    private static final class ThrowingExecutor implements ToolExecutor {
        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                                                      InvocationContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class SlowExecutor implements ToolExecutor {
        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            sleep();
            return "late";
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                                                      InvocationContext context) {
            sleep();
            return ToolExecutionResult.builder().resultText("late").build();
        }

        private void sleep() {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolCancelledException();
            }
        }
    }

    private static final class FakeGate implements ToolApprovalGate {
        int calls;
        final Decision answer;

        FakeGate(Decision answer) {
            this.answer = answer;
        }

        @Override
        public Decision request(ApprovalRequest request) throws InterruptedException {
            calls++;
            if (answer == null) {
                throw new InterruptedException("stopped");
            }
            return answer;
        }
    }
}
