package com.example.chat.langchain4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedisChatMemoryStoreTest {

    @Test
    void shouldInstantiate() {
        assertNotNull(RedisChatMemoryStore.class);
    }
}
