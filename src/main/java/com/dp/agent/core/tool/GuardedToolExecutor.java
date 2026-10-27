package com.dp.agent.core.tool;

import com.dp.agent.config.ToolApprovalPolicy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 工具执行装饰器：审批 → 执行（带超时）→ 审计。
 * 工具异常不再中断整轮 ReAct：除用户取消外，都转换为 isError 的工具结果让模型自行恢复。
 */
public final class GuardedToolExecutor implements ToolExecutor {

    private static final int ARGS_LIMIT = 800;
    private static final int ERROR_LIMIT = 300;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String DENIED_TEXT = "工具调用已被用户拒绝。";
    private static final String TIMEOUT_TEXT = "工具调用确认超时，已拒绝。";
    private static final ExecutorService TOOL_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "tool-executor");
                thread.setDaemon(true);
                return thread;
            });

    private final String name;
    private final ToolExecutor delegate;
    private final boolean safe;
    private final ToolRuntime runtime;
    private final int timeoutSeconds;

    public GuardedToolExecutor(String name, ToolExecutor delegate, boolean safe,
                               ToolRuntime runtime) {
        this(name, delegate, safe, runtime, DEFAULT_TIMEOUT_SECONDS);
    }

    GuardedToolExecutor(String name, ToolExecutor delegate, boolean safe,
                        ToolRuntime runtime, int timeoutSeconds) {
        this.name = name;
        this.delegate = delegate;
        this.safe = safe;
        this.runtime = runtime;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        long start = System.currentTimeMillis();
        Authorization authorization = authorize(request, start);
        if (authorization.denial() != null) {
            return authorization.denial();
        }
        try {
            String result = runWithTimeout(() -> delegate.execute(request, memoryId));
            audit(request, authorization.decision(), start, true, null);
            return result;
        } catch (ToolCancelledException e) {
            throw e;
        } catch (ToolTimeoutException e) {
            String message = timeoutMessage();
            audit(request, authorization.decision(), start, false, "timeout");
            return message;
        } catch (RuntimeException e) {
            String message = failureMessage(e);
            audit(request, authorization.decision(), start, false, message);
            return message;
        }
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                                                  InvocationContext context) {
        long start = System.currentTimeMillis();
        Authorization authorization = authorize(request, start);
        if (authorization.denial() != null) {
            return errorResult(authorization.denial());
        }
        try {
            ToolExecutionResult result = runWithTimeout(
                    () -> delegate.executeWithContext(request, context));
            boolean success = result == null || !result.isError();
            audit(request, authorization.decision(), start, success,
                    success ? null : resultText(result));
            return result;
        } catch (ToolCancelledException e) {
            throw e;
        } catch (ToolTimeoutException e) {
            String message = timeoutMessage();
            audit(request, authorization.decision(), start, false, "timeout");
            return errorResult(message);
        } catch (RuntimeException e) {
            String message = failureMessage(e);
            audit(request, authorization.decision(), start, false, message);
            return errorResult(message);
        }
    }

    @Override
    public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request,
                                                               InvocationContext context) {
        try {
            return CompletableFuture.completedFuture(executeWithContext(request, context));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 工具在独立线程执行，超时/取消都不会永久占用 Agent 工作线程。 */
    private <T> T runWithTimeout(Callable<T> call) {
        Future<T> future = TOOL_EXECUTOR.submit(call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolCancelledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolCancelledException cancelled) {
                throw cancelled;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        }
    }

    private Authorization authorize(ToolExecutionRequest request, long start) {
        boolean requireApproval = !safe || runtime.policy() == ToolApprovalPolicy.ALWAYS;
        if (!requireApproval) {
            return new Authorization("AUTO_APPROVED", null);
        }

        ToolApprovalGate gate = runtime.gate();
        ToolApprovalGate.Decision decision;
        try {
            decision = gate == null ? ToolApprovalGate.Decision.DENIED
                    : gate.request(new ToolApprovalGate.ApprovalRequest(
                            runtime.sessionId(), name, request.arguments(), safe));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit(request, "CANCELLED", start, false, "interrupted");
            throw new ToolCancelledException();
        }

        if (decision == null || decision == ToolApprovalGate.Decision.DENIED) {
            audit(request, "DENIED", start, false, DENIED_TEXT);
            return new Authorization("DENIED", DENIED_TEXT);
        }
        if (decision == ToolApprovalGate.Decision.TIMEOUT) {
            audit(request, "TIMEOUT", start, false, TIMEOUT_TEXT);
            return new Authorization("TIMEOUT", TIMEOUT_TEXT);
        }
        if (decision == ToolApprovalGate.Decision.CANCELLED) {
            audit(request, "CANCELLED", start, false, "cancelled");
            throw new ToolCancelledException();
        }
        return new Authorization("APPROVED", null);
    }

    private void audit(ToolExecutionRequest request, String decision, long start,
                       boolean success, String error) {
        ToolAuditLog.Event event = new ToolAuditLog.Event(
                Instant.now().toString(),
                runtime.sessionId(),
                name,
                truncate(request.arguments(), ARGS_LIMIT),
                safe,
                decision,
                System.currentTimeMillis() - start,
                success,
                truncate(error, ERROR_LIMIT));
        if (!runtime.audit().append(event) && runtime.audit().warnOnce()) {
            Consumer<String> trace = runtime.trace();
            if (trace != null) {
                trace.accept("工具审计日志写入失败：" + runtime.audit().lastError());
            }
        }
    }

    private ToolExecutionResult errorResult(String text) {
        return ToolExecutionResult.builder().isError(true).resultText(text).build();
    }

    private String timeoutMessage() {
        return "工具执行超时（" + timeoutSeconds + " 秒）：" + name;
    }

    private static String failureMessage(Throwable error) {
        String detail = error == null ? null : error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error == null ? "unknown" : error.getClass().getSimpleName();
        }
        return truncate("工具执行失败：" + detail, ERROR_LIMIT);
    }

    private static String resultText(ToolExecutionResult result) {
        return result == null ? null : result.resultText();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private record Authorization(String decision, String denial) {
    }

    /** 内部超时信号，不暴露给模型。 */
    private static final class ToolTimeoutException extends RuntimeException {
    }
}
