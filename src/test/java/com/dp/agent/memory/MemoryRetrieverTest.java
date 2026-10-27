package com.dp.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRetrieverTest {

    private static MemoryEntry general(String text) {
        return MemoryEntry.manual(MemoryKind.GENERAL, text, true, true, 5);
    }

    private static MemoryEntry project(String text) {
        return MemoryEntry.manual(MemoryKind.PROJECT, text, false, false, 4);
    }

    @Test
    void generalMemoryIsAlwaysIncludedAndRelevantLongTermIsSelected() {
        MemoryEntry general = general("回答尽量简洁");
        MemoryEntry project = project("项目使用 JavaFX 做桌面应用");
        MemoryEntry unrelated = MemoryEntry.manual(
                MemoryKind.FACT, "用户喜欢喝咖啡", false, false, 2);

        List<MemoryEntry> selected = MemoryRetriever.select(
                List.of(general, project, unrelated), "JavaFX 桌面应用怎么打包", 5, 500);

        assertTrue(selected.contains(general));
        assertTrue(selected.contains(project));
        assertTrue(!selected.contains(unrelated));
    }

    @Test
    void unrelatedQueryOnlyReturnsGeneralMemory() {
        MemoryEntry general = general("所有回答使用中文");
        MemoryEntry project = project("项目使用 JavaFX 做桌面应用");

        List<MemoryEntry> selected = MemoryRetriever.select(
                List.of(general, project), "今天天气怎么样", 5, 500);

        assertEquals(1, selected.size());
        assertEquals(general.id(), selected.get(0).id());
    }

    @Test
    void relevantMemoryGetsBudgetBeforeSecondGeneralMemory() {
        MemoryEntry firstGeneral = general("G".repeat(60));
        MemoryEntry secondGeneral = general("H".repeat(60));
        MemoryEntry relevant = project("项目使用 JavaFX 开发桌面应用");

        List<MemoryEntry> selected = MemoryRetriever.select(
                List.of(firstGeneral, secondGeneral, relevant),
                "JavaFX 开发", 5, 120);

        assertTrue(selected.contains(relevant),
                "通用记忆不应把相关长期记忆挤出预算");
    }

    @Test
    void characterBudgetIsRespectedForAdditionalEntries() {
        MemoryEntry first = general("A".repeat(60));
        MemoryEntry second = general("B".repeat(60));

        List<MemoryEntry> selected = MemoryRetriever.select(
                List.of(first, second), "anything", 5, 80);

        assertEquals(1, selected.size());
        assertEquals(first.id(), selected.get(0).id());
    }
}
