package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class WeatherToolTest {

    @Test
    void shouldImplementToolInterface() {
        assertTrue(
            com.example.chat.agent.tool.Tool.class.isAssignableFrom(WeatherTool.class),
            "WeatherTool should implement Tool interface"
        );
    }

    @Test
    void shouldHaveSpringComponentAnnotation() {
        assertTrue(
            WeatherTool.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "WeatherTool should have @Component annotation"
        );
    }

    @Test
    void shouldReturnErrorForMissingParameter() {
        WeatherTool tool = new WeatherTool();
        String result = tool.execute(Map.of());
        assertNotNull(result);
        assertTrue(result.contains("缺少参数"), "Should return missing parameter message, got: " + result);
    }

    @Test
    void shouldHaveCorrectName() {
        WeatherTool tool = new WeatherTool();
        assertEquals("weather", tool.getName());
    }

    @Test
    void shouldHaveDescription() {
        WeatherTool tool = new WeatherTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }
}
