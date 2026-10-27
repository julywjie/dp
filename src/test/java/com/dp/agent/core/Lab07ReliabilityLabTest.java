package com.dp.agent.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 7：并发控制——单飞 CAS 与终态门禁（AI-KNOWLEDGE.md 第 7 章配套）。
 * 运行：mvn test -Dtest=Lab07ReliabilityLabTest -Dsurefire.useFile=false
 */
class Lab07ReliabilityLabTest {

    @Test
    void exp1_singleFlightCas() throws Exception {
        System.out.println("── 实验 7.1 银行叫号机：两个请求同时抢，只有一个能进 ──");
        AtomicBoolean busy = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);
        StringBuilder report = new StringBuilder();
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 1; i <= 2; i++) {
            int id = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    return;
                }
                boolean admitted = busy.compareAndSet(false, true);   // 抢号
                report.append("请求 ").append(id).append(admitted ? " 进入执行\n" : " 被拒（请稍后再试）\n");
                done.countDown();   // 赢家不释放，直到两个请求都完成抢号
            });
            t.start();
        }
        start.countDown();
        done.await();
        System.out.print(report);
        System.out.println("→ compareAndSet(false, true) 是原子动作：检查+占位一步完成，绝不放两个人进同一段临界区");
        assertTrue(report.toString().contains("进入执行"));
        assertTrue(report.toString().contains("被拒"));
    }

    @Test
    void exp2_terminalGateFiresOnlyOnce() {
        System.out.println("── 实验 7.2 颁奖礼只颁一次：终态门禁 ──");
        CountingListener delegate = new CountingListener();
        AtomicBoolean terminal = new AtomicBoolean(false);
        AgentService.Listener gated =
                new AgentService.TerminalListener(delegate, terminal);

        gated.onError("已停止：后台请求仍在退出…");     // 停止看门狗先到
        gated.onComplete("这是迟到的最终答案…");        // 工作线程后到
        gated.onError("迟到的报错");                    // 再来一个

        System.out.println("第一次回调（onError）: 已放行 → delegate.onErrorCount=1");
        System.out.println("第二次回调（onComplete，迟到）: 被 CAS 拦下 → delegate.completeCount="
                + delegate.completeCount);
        System.out.println("第三次回调（onError，迟到）: 同样被拦 → delegate.errorCount="
                + delegate.errorCount);
        System.out.println("→ UI 永远只收到一个终态，不会「先说已停止、又说回答完成」");
        assertEquals(1, delegate.errorCount);
        assertEquals(0, delegate.completeCount);
    }

    private static final class CountingListener implements AgentService.Listener {
        int completeCount;
        int errorCount;

        @Override
        public void onTrace(String event) {
        }

        @Override
        public void onComplete(String answer) {
            completeCount++;
        }

        @Override
        public void onError(String message) {
            errorCount++;
        }
    }
}
