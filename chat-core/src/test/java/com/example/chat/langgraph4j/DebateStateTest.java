package com.example.chat.langgraph4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebateStateTest {

    @Test
    void shouldInstantiateWithSetters() {
        DebateState state = new DebateState();
        state.setTopic("测试话题");
        assertNotNull(state);
        assertEquals("测试话题", state.getTopic());
    }

    @Test
    void shouldReturnDefaultTopic() {
        DebateState state = new DebateState();
        assertEquals("", state.getTopic());
    }

    @Test
    void shouldReturnDefaultUserId() {
        DebateState state = new DebateState();
        assertEquals(0L, state.getUserId());
    }

    @Test
    void shouldReturnDefaultRounds() {
        DebateState state = new DebateState();
        assertEquals(0, state.getCurrentRound());
        assertEquals(3, state.getMaxRounds());
    }

    @Test
    void shouldNeedMoreRounds() {
        DebateState state = new DebateState();
        state.setMaxRounds(3);
        state.setCurrentRound(1);
        assertTrue(state.needMoreRounds());
    }

    @Test
    void shouldNotNeedMoreRoundsWhenReached() {
        DebateState state = new DebateState();
        state.setMaxRounds(3);
        state.setCurrentRound(3);
        assertFalse(state.needMoreRounds());
    }
}
