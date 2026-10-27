package com.dp.agent.core;

import dev.langchain4j.agent.tool.Tool;
import net.objecthunter.exp4j.ExpressionBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Agent 可调用的演示工具集（@Tool 方法由 LangGraph4j 的
 * toolsFromObject 自动扫描并注册为函数调用规范）。
 * 扩展方式：新增一个 @Tool 方法即可，无需改动编排层。
 */
public class AgentTools {

    @Tool("计算一个数学表达式的值。支持四则运算、括号与常用函数，例如: (1+2)*3.5、sqrt(16)、2^10")
    public double calculator(String expression) {
        return new ExpressionBuilder(expression.trim()).build().evaluate();
    }

    @Tool("获取当前的日期、时间、星期与时区信息，无参数")
    public String currentTime() {
        ZoneId zone = ZoneId.systemDefault();
        String time = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT));
        String week = LocalDateTime.now().getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.CHINESE);
        return time + " " + week + " (" + zone + ")";
    }

    @Tool("自动识别并格式化 JSON 或 XML 文本，返回缩进排版后的结果。参数 text 为待格式化的原始文本")
    public String formatData(String text) {
        try {
            DataFormatter.Result result = DataFormatter.format(text);
            return "格式化成功 (" + result.kind() + "):\n" + result.output();
        } catch (DataFormatter.FormatException e) {
            return "格式化失败: " + e.getMessage();
        }
    }

    @Tool("将 JSON 文本压缩为单行（去掉缩进与换行），参数 text 为原始 JSON 文本")
    public String minifyJson(String text) {
        try {
            return DataFormatter.minifyJson(text);
        } catch (DataFormatter.FormatException e) {
            return "压缩失败: " + e.getMessage();
        }
    }
}
