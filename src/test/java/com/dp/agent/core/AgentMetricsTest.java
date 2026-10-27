package com.dp.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMetricsTest {

    @Test
    void formatsSecondsAndTraceLine() {
        AgentMetrics metrics = new AgentMetrics(1234, 5, 2, 1, 1000, 200);

        assertEquals("1.2s", metrics.durationText());
        String line = metrics.traceLine();
        assertTrue(line.contains("1.2s"));
        assertTrue(line.contains("5 步"));
        assertTrue(line.contains("工具 2 次"));
        assertTrue(line.contains("记忆 1 条"));
        assertTrue(line.contains("tokens≈1000/200"));
    }

    @Test
    void formatsLongDurationAsMinutes() {
        assertEquals("1m 5s", new AgentMetrics(65_000, 1, 0, 0, 1, 1)
                .durationText());
    }
}
