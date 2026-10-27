package com.dp.agent.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 8：双轨会话——失败撤单与重试不重下单（AI-KNOWLEDGE.md 第 4.5 节配套）。
 * 运行：mvn test -Dtest=Lab08ConversationLabTest -Dsurefire.useFile=false
 */
class Lab08ConversationLabTest {

    @Test
    void exp1_rollbackOnFailure() {
        System.out.println("── 实验 8.1 失败撤单：报错的一轮不留在 history ──");
        Conversation conversation = new Conversation();
        conversation.addUser("你好，介绍下你自己");
        conversation.addAssistant("我是桌面助手。");
        conversation.addUser("帮我算 2^10");        // 本轮失败了
        System.out.println("失败前 history 条数: " + conversation.historySnapshot().size()
                + "（含本轮用户输入）");

        conversation.rollbackLastUser();
        System.out.println("撤单后 history 条数: " + conversation.historySnapshot().size()
                + " → 最后一轮用户输入被移除，重发即新一轮");
        assertEquals(2, conversation.historySnapshot().size());
    }

    @Test
    void exp2_retryKeepsQuestionOnce() {
        System.out.println("── 实验 8.2 重试不重下单：问题保留、回答移除 ──");
        Conversation conversation = new Conversation();
        conversation.addUser("帮我算 2^10");
        conversation.addAssistant("1024（这版答案不满意，要重新生成）");

        String prompt = conversation.prepareRetry();

        System.out.println("prepareRetry() 返回待重跑的问题: 「" + prompt + "」");
        System.out.println("history 现在以谁结尾: "
                + conversation.historySnapshot()
                        .get(conversation.historySnapshot().size() - 1).type());
        System.out.println("→ 之后以 appendUser=false 运行：问题已在单子上，不点两遍菜");
        assertEquals("帮我算 2^10", prompt);
        assertTrue(conversation.historySnapshot().size() == 1);
        assertNull(new Conversation().prepareRetry(), "没有可重试的回答时返回 null");
    }

    @Test
    void exp3_compactionProgressIsClamped() {
        System.out.println("── 实验 8.3 压缩进度钳制：covered 不能超过 history 长度 ──");
        Conversation conversation = new Conversation();
        conversation.addUser("问题一");
        conversation.setCompaction("旧摘要", 5);   // 钳制到 history.size()=1
        System.out.println("写入 covered=5（history 只有 1 条）→ 实际保存 summarizedCount="
                + conversation.summarizedCount());
        System.out.println("→ 防止会话恢复后摘要进度越界，重启不会重复压缩");
        assertEquals(1, conversation.summarizedCount());
        assertEquals("旧摘要", conversation.summary());
    }
}
