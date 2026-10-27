package com.dp.agent.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentServiceTest {

    @Test
    void terminalListenerDeliversOnlyFirstTerminalCallback() {
        AtomicBoolean terminal = new AtomicBoolean(false);
        FakeListener delegate = new FakeListener();
        AgentService.TerminalListener listener =
                new AgentService.TerminalListener(delegate, terminal);

        listener.onTrace("trace");
        listener.onComplete("answer");
        listener.onError("late error");
        listener.onToken("late token");

        assertEquals(1, delegate.traces);
        assertEquals(1, delegate.completes);
        assertEquals(0, delegate.errors);
        assertEquals(0, delegate.tokens);
    }

    @Test
    void errorWinsOverLateCompletion() {
        AtomicBoolean terminal = new AtomicBoolean(false);
        FakeListener delegate = new FakeListener();
        AgentService.TerminalListener listener =
                new AgentService.TerminalListener(delegate, terminal);

        listener.onError("stopped");
        listener.onComplete("late answer");

        assertEquals(1, delegate.errors);
        assertEquals(0, delegate.completes);
    }

    private static final class FakeListener implements AgentService.Listener {
        int traces;
        int tokens;
        int completes;
        int errors;

        @Override
        public void onToken(String chunk) {
            tokens++;
        }

        @Override
        public void onTrace(String event) {
            traces++;
        }

        @Override
        public void onComplete(String answer) {
            completes++;
        }

        @Override
        public void onError(String message) {
            errors++;
        }
    }
}
