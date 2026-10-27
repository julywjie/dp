package com.dp.agent.core;

import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 6：消息四角色与 ReAct 消息演化（AI-KNOWLEDGE.md 第 1、2 章配套）。
 * 运行：mvn test -Dtest=Lab06MessageShapeLabTest -Dsurefire.useFile=false
 */
class Lab06MessageShapeLabTest {

    @Test
    void exp1_fourMessageRoles() {
        System.out.println("── 实验 6.1 四色便签：一次请求里四种消息长什么样 ──");
        SystemMessage system = new SystemMessage("你是一个桌面助手");
        UserMessage user = new UserMessage("2 的 10 次方是多少？");
        dev.langchain4j.agent.tool.ToolExecutionRequest toolRequest =
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call-1").name("calculator")
                        .arguments("{\"expression\":\"2^10\"}").build();
        AiMessage toolCall = AiMessage.from(toolRequest);
        ToolExecutionResultMessage toolResult =
                ToolExecutionResultMessage.from(toolRequest, "1024.0");

        System.out.println("[System] " + system);
        System.out.println("[User]   " + user.singleText());
        System.out.println("[Assistant·工具请求] name=" + toolCall.toolExecutionRequests().get(0).name()
                + " arguments=" + toolCall.toolExecutionRequests().get(0).arguments());
        System.out.println("[Tool]   " + toolResult.text());
        System.out.println("→ 蓝便签有两种用途：纯文本回答，或一张「工具调用请求单」");
        assertEquals(4, List.of(system, user, toolCall, toolResult).size());
    }

    @Test
    void exp2_toolAnnotationBecomesSchema() throws Exception {
        System.out.println("── 实验 6.2 @Tool 方法被印成 JSON Schema 菜单 ──");
        Method method = AgentTools.class.getMethod("calculator", String.class);
        var spec = ToolSpecifications.toolSpecificationFrom(method);
        System.out.println("工具名   : " + spec.name());
        System.out.println("描述     : " + spec.description());
        System.out.println("参数     : " + spec.parameters().properties().keySet()
                + " → 模型看到的是「expression」，不是 arg0");
        assertEquals("calculator", spec.name());
        assertTrue(spec.description().contains("sqrt"));
    }

    @Test
    void exp3_reactMessageEvolution() {
        System.out.println("── 实验 6.3 亲眼看 messages 草稿纸一轮轮变长（ReAct） ──");
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage("2 的 10 次方是多少？"));
        dump("① 用户提问后", messages);

        dev.langchain4j.agent.tool.ToolExecutionRequest toolRequest =
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call-1").name("calculator")
                        .arguments("{\"expression\":\"2^10\"}").build();
        AiMessage request = AiMessage.from(toolRequest);
        messages.add(request);
        dump("② agent 节点：模型决定调工具", messages);

        messages.add(ToolExecutionResultMessage.from(toolRequest, "1024.0"));
        dump("③ action 节点：工具结果回填", messages);

        messages.add(new AiMessage("2 的 10 次方等于 1024。"));
        dump("④ agent 节点：模型给出最终回答 → END", messages);

        System.out.println("→ 这块不断追加的告示板就是 AgentExecutor.State.messages");
        System.out.println("→ 条件边看的正是 ②：有 toolExecutionRequests → 去 action；没有 → END");
        assertEquals(4, messages.size());
    }

    private static void dump(String title,
                             List<dev.langchain4j.data.message.ChatMessage> messages) {
        System.out.println(title + "（共 " + messages.size() + " 条）");
        for (dev.langchain4j.data.message.ChatMessage m : messages) {
            String brief = m instanceof UserMessage u ? u.singleText()
                    : m instanceof AiMessage a ? (a.hasToolExecutionRequests()
                            ? "[请求调工具] " + a.toolExecutionRequests().get(0).name()
                                    + a.toolExecutionRequests().get(0).arguments()
                            : a.text())
                    : m instanceof ToolExecutionResultMessage t ? "[工具结果] " + t.text() : "?";
            System.out.println("     " + m.type() + " : " + brief);
        }
    }
}
