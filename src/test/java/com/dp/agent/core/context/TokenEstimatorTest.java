package com.dp.agent.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void estimatesAsciiAndCjk() {
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(3, TokenEstimator.estimate("abcdefghijkl"));
        assertEquals(4, TokenEstimator.estimate("你好世界"));
    }

    @Test
    void growsWithLength() {
        int shortText = TokenEstimator.estimate("你好");
        int longText = TokenEstimator.estimate("你好，请帮我总结一下这段对话的要点。");
        assertTrue(longText > shortText);
    }
}
