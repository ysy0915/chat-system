package com.example.chat.langgraph4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebateGraphServiceTest {

    @Test
    void shouldInstantiate() {
        DebateGraphService service = new DebateGraphService();
        assertNotNull(service);
    }
}
