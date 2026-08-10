package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TimeToolTest {

    @Test
    void shouldImplementToolInterface() {
        assertTrue(
            com.example.chat.agent.tool.Tool.class.isAssignableFrom(TimeTool.class),
            "TimeTool should implement Tool interface"
        );
    }

    @Test
    void shouldHaveSpringComponentAnnotation() {
        assertTrue(
            TimeTool.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "TimeTool should have @Component annotation"
        );
    }

    @Test
    void shouldExecuteTimeQuery() {
        TimeTool tool = new TimeTool();
        String result = tool.execute(Map.of());
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("当前时间") || result.contains("今天日期"),
            "Result should contain time/date info, got: " + result);
    }

    @Test
    void shouldHaveCorrectName() {
        TimeTool tool = new TimeTool();
        assertEquals("time", tool.getName());
    }

    @Test
    void shouldHaveDescription() {
        TimeTool tool = new TimeTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }
}
