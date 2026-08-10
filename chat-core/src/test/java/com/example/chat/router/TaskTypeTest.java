package com.example.chat.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskTypeTest {

    @Test
    @DisplayName("枚举值完整")
    void testEnumValues() {
        assertEquals(8, TaskType.values().length);
        assertEquals(TaskType.SIMPLE_CHAT, TaskType.valueOf("SIMPLE_CHAT"));
        assertEquals(TaskType.COMPLEX_REASONING, TaskType.valueOf("COMPLEX_REASONING"));
        assertEquals(TaskType.VISION, TaskType.valueOf("VISION"));
        assertEquals(TaskType.SUMMARIZATION, TaskType.valueOf("SUMMARIZATION"));
        assertEquals(TaskType.CREATIVE, TaskType.valueOf("CREATIVE"));
        assertEquals(TaskType.CODE, TaskType.valueOf("CODE"));
        assertEquals(TaskType.EMOTIONAL, TaskType.valueOf("EMOTIONAL"));
        assertEquals(TaskType.DEBATE, TaskType.valueOf("DEBATE"));
    }
}
