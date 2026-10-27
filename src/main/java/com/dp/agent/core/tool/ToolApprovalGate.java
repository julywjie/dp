package com.dp.agent.core.tool;

/** 工具执行前的审批入口；实现方负责把请求切到 UI 线程并等待用户决定。 */
public interface ToolApprovalGate {

    enum Decision {
        APPROVED, DENIED, TIMEOUT, CANCELLED
    }

    record ApprovalRequest(String sessionId, String toolName, String arguments, boolean safe) {
    }

    Decision request(ApprovalRequest request) throws InterruptedException;

    /** 无 UI 环境下的保守默认：一律拒绝。 */
    ToolApprovalGate DENY_ALL = request -> Decision.DENIED;
}
