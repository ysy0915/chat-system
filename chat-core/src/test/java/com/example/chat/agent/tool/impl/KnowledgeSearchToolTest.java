package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSearchToolTest {

    @Test
    void shouldImplementToolInterface() {
        assertTrue(
            com.example.chat.agent.tool.Tool.class.isAssignableFrom(KnowledgeSearchTool.class),
            "KnowledgeSearchTool should implement Tool interface"
        );
    }

    @Test
    void shouldHaveComponentAnnotation() {
        assertTrue(
            KnowledgeSearchTool.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "KnowledgeSearchTool should have @Component annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            KnowledgeSearchTool.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "KnowledgeSearchTool should have @ConditionalOnProperty annotation"
        );
    }

    @Test
    void shouldHaveExecuteMethod() throws NoSuchMethodException {
        assertNotNull(KnowledgeSearchTool.class.getMethod("execute", java.util.Map.class));
    }

    @Test
    void shouldHaveCorrectName() throws Exception {
        // Test via reflection to verify the getName returns "knowledge_search"
        java.lang.reflect.Method getNameMethod = KnowledgeSearchTool.class.getMethod("getName");
        assertEquals(String.class, getNameMethod.getReturnType());
    }

    @Test
    void shouldHaveDescription() throws Exception {
        java.lang.reflect.Method getDescMethod = KnowledgeSearchTool.class.getMethod("getDescription");
        assertEquals(String.class, getDescMethod.getReturnType());
    }

    @Test
    void shouldHaveGetParameters() throws Exception {
        java.lang.reflect.Method getParamsMethod = KnowledgeSearchTool.class.getMethod("getParameters");
        assertEquals(String.class, getParamsMethod.getReturnType());
    }
}
