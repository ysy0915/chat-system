package com.example.chat.langgraph4j;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DebateStateTest {

    @Test
    void shouldInstantiateWithInitData() {
        Map<String, Object> initData = new HashMap<>();
        initData.put(DebateState.TOPIC, "测试话题");
        DebateState state = new DebateState(initData);
        assertNotNull(state);
        assertEquals("测试话题", state.getTopic());
    }

    @Test
    void shouldReturnDefaultTopic() {
        DebateState state = new DebateState(new HashMap<>());
        assertEquals("", state.getTopic());
    }

    @Test
    void shouldReturnDefaultUserId() {
        DebateState state = new DebateState(new HashMap<>());
        assertEquals(0L, state.getUserId());
    }

    @Test
    void shouldReturnDefaultRounds() {
        DebateState state = new DebateState(new HashMap<>());
        assertEquals(0, state.getCurrentRound());
        assertEquals(3, state.getMaxRounds());
    }

    @Test
    void shouldNeedMoreRounds() {
        Map<String, Object> initData = new HashMap<>();
        initData.put(DebateState.MAX_ROUNDS, 3);
        initData.put(DebateState.CURRENT_ROUND, 1);
        DebateState state = new DebateState(initData);
        assertTrue(state.needMoreRounds());
    }

    @Test
    void shouldNotNeedMoreRoundsWhenReached() {
        Map<String, Object> initData = new HashMap<>();
        initData.put(DebateState.MAX_ROUNDS, 3);
        initData.put(DebateState.CURRENT_ROUND, 3);
        DebateState state = new DebateState(initData);
        assertFalse(state.needMoreRounds());
    }
}
