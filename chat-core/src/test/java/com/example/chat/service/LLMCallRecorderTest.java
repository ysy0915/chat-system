package com.example.chat.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LLMCallRecorderTest {

    @Test
    void shouldInstantiate() {
        LLMCallRecorder recorder = new LLMCallRecorder();
        assertNotNull(recorder);
    }
}
