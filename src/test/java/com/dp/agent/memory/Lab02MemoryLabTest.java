package com.dp.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 2：记忆检索——没有 embedding 也能召回（AI-KNOWLEDGE.md 第 5 章配套）。
 * 运行：mvn test -Dtest=Lab02MemoryLabTest -Dsurefire.useFile=false
 */
class Lab02MemoryLabTest {

    @Test
    void exp1_bigramTokenization() {
        System.out.println("── 实验 2.1 中文没有空格？切成两字滑窗（bigram） ──");
        Set<String> tokens = MemoryRetriever.tokens("北京天气");
        System.out.println("「北京天气」→ " + new TreeSet<>(tokens));
        System.out.println("「KOSPI 现在多少点」→ " + new TreeSet<>(MemoryRetriever.tokens("KOSPI 现在多少点")));
        assertEquals(Set.of("北京", "京天", "天气"), tokens);
    }

    @Test
    void exp2_pronounResolutionImprovesRecall() {
        System.out.println("── 实验 2.2 「那它的价格呢」——它是指谁？召回对比 ──");
        List<MemoryEntry> memories = List.of(
                MemoryEntry.auto(MemoryKind.FACT, "用户关注韩国 KOSPI 指数与日经225的行情", 3, "s1"),
                MemoryEntry.auto(MemoryKind.PREFERENCE, "用户偏好深色主题界面", 3, "s1"),
                MemoryEntry.auto(MemoryKind.FACT, "用户在做 Java 桌面项目", 3, "s1"));

        String followUpOnly = "那它的价格呢？";
        String withContext = "KOSPI 现在多少点？那它的价格呢？";   // memoryQuery 的拼接策略
        for (String query : new String[]{followUpOnly, withContext}) {
            List<MemoryEntry> hits = MemoryRetriever.select(memories, query, 8, 1000);
            System.out.println("query = 「" + query + "」");
            for (MemoryEntry hit : hits) {
                System.out.println("   召回 → " + hit.text());
            }
            if (hits.isEmpty()) {
                System.out.println("   （零命中：query 里没有任何实体词）");
            }
        }
        assertTrue(MemoryRetriever.select(memories, withContext, 8, 1000)
                .stream().anyMatch(m -> m.text().contains("KOSPI")));
    }

    @Test
    void exp3_recencyHalfLife() {
        System.out.println("── 实验 2.3 记忆的新鲜度奖励：半衰期约 62 天 ──");
        for (int days : new int[]{0, 31, 62, 180, 365}) {
            System.out.printf("距今天数 %3d → 新鲜度加成 %.4f%n",
                    days, 0.05 * Math.exp(-days / 90.0));
        }
        System.out.println("（62 天正好衰减一半：0.05 × e^(−62/90) ≈ 0.025）");
        assertTrue(0.05 * Math.exp(-62 / 90.0) < 0.05 / 1.9);
    }

    @Test
    void exp4_jaccardDedup() {
        System.out.println("── 实验 2.4 记忆去重第三级：Jaccard 相似度 ≥ 0.8 判重 ──");
        String oldText = "用户喜欢深色主题界面";
        String newText = "喜欢深色主题";
        Set<String> a = MemoryRetriever.tokens(oldText);
        Set<String> b = MemoryRetriever.tokens(newText);
        double intersection = 0;
        for (String token : b) {
            if (a.contains(token)) {
                intersection++;
            }
        }
        double union = a.size() + b.size() - intersection;
        double jaccard = intersection / union;
        System.out.printf("「%s」∩「%s」%nJaccard = %.2f（阈值 0.8 未到）%n",
                oldText, newText, jaccard);
        System.out.println("但走第二级判定：短串「" + newText + "」完全被长串包含 → 判重，");
        System.out.println("合并时保留信息更完整的长串");
        assertTrue(oldText.contains(newText));
        assertEquals(5, b.size(), "「喜欢深色主题」的 bigram: 喜欢/欢深/深色/色主/主题");
    }
}
