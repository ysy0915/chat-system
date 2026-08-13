package com.example.chat.agent.tool;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolTest {

    @Test
    void shouldBeInterface() {
        assertTrue(Tool.class.isInterface(), "Tool should be an interface");
    }

    @Test
    void shouldHaveGetNameMethod() throws NoSuchMethodException {
        assertNotNull(Tool.class.getMethod("getName"));
    }

    @Test
    void shouldHaveGetDescriptionMethod() throws NoSuchMethodException {
        assertNotNull(Tool.class.getMethod("getDescription"));
    }

    @Test
    void shouldHaveGetParametersMethod() throws NoSuchMethodException {
        assertNotNull(Tool.class.getMethod("getParameters"));
    }

    @Test
    void shouldHaveExecuteMethod() throws NoSuchMethodException {
        assertNotNull(Tool.class.getMethod("execute", Map.class));
    }
}
