package com.dp.agent.core.tool;

import com.dp.agent.core.AgentTools;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void scansBuiltInToolsAndMarksThemSafe() {
        ToolRegistry registry = ToolRegistry.scan(new AgentTools());
        assertEquals(4, registry.size());
        assertTrue(registry.names().containsAll(
                java.util.List.of("calculator", "currentTime", "formatData", "minifyJson")));
        registry.names().forEach(name -> assertTrue(registry.isSafe(name), name));
    }

    @Test
    void unknownToolRequiresApproval() {
        ToolRegistry registry = ToolRegistry.scan(new ExtraTools());
        assertEquals(1, registry.size());
        assertFalse(registry.isSafe("echo"));
    }

    @Test
    void duplicateNamesAreDeduplicated() {
        ToolRegistry registry = ToolRegistry.scan(new ExtraTools(), new ExtraTools());
        assertEquals(1, registry.size());
    }

    static final class ExtraTools {
        @Tool("测试用：原样返回输入")
        public String echo(String text) {
            return text;
        }
    }
}
