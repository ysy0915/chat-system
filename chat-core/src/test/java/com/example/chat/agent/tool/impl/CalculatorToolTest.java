package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CalculatorToolTest {

    @Test
    void shouldImplementToolInterface() {
        assertTrue(
            com.example.chat.agent.tool.Tool.class.isAssignableFrom(CalculatorTool.class),
            "CalculatorTool should implement Tool interface"
        );
    }

    @Test
    void shouldHaveSpringComponentAnnotation() {
        assertTrue(
            CalculatorTool.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "CalculatorTool should have @Component annotation"
        );
    }

    @Test
    void shouldExecuteSimpleArithmetic() {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(Map.of("expression", "2 + 3"));
        assertNotNull(result);
        assertTrue(result.contains("5"), "Result should contain 5, got: " + result);
    }

    @Test
    void shouldExecuteMultiplication() {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(Map.of("expression", "4 * 5"));
        assertNotNull(result);
        assertTrue(result.contains("20"), "Result should contain 20, got: " + result);
    }

    @Test
    void shouldExecuteDivision() {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(Map.of("expression", "10 / 2"));
        assertNotNull(result);
        assertTrue(result.contains("5"), "Result should contain 5, got: " + result);
    }

    @Test
    void shouldExecuteSubtraction() {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(Map.of("expression", "10 - 3"));
        assertNotNull(result);
        assertTrue(result.contains("7"), "Result should contain 7, got: " + result);
    }

    @Test
    void shouldReturnErrorForInvalidExpression() {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(Map.of("expression", "abc"));
        assertNotNull(result);
        assertTrue(result.contains("非法字符") || result.contains("计算失败"),
            "Should return error message for invalid expression, got: " + result);
    }

    @Test
    void shouldReturnErrorForMissingParameter() {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(Map.of());
        assertNotNull(result);
        assertTrue(result.contains("缺少参数"), "Should return missing parameter message, got: " + result);
    }

    @Test
    void shouldHaveCorrectName() {
        CalculatorTool tool = new CalculatorTool();
        assertEquals("calculator", tool.getName());
    }

    @Test
    void shouldHaveDescription() {
        CalculatorTool tool = new CalculatorTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }
}
