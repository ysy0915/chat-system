package com.example.chat.router;

import com.example.chat.entity.ModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingDecisionTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        RoutingDecision rd = new RoutingDecision();
        rd.setTaskType(TaskType.SIMPLE_CHAT);
        rd.setSelectedModel("gpt-4");
        rd.setSelectedProvider("openai");
        rd.setSelectedModelId(1L);
        rd.setReason("default");
        rd.setAlternatives(List.of("gpt-3.5"));

        assertEquals(TaskType.SIMPLE_CHAT, rd.getTaskType());
        assertEquals("gpt-4", rd.getSelectedModel());
        assertEquals("openai", rd.getSelectedProvider());
        assertEquals(1L, rd.getSelectedModelId());
        assertEquals("default", rd.getReason());
        assertEquals(1, rd.getAlternatives().size());
    }

    @Test
    @DisplayName("带 ModelConfig 的构造函数")
    void testConstructorWithConfig() {
        ModelConfig mc = new ModelConfig();
        mc.id = 1L;
        mc.model = "gpt-4";
        mc.provider = "openai";

        RoutingDecision rd = new RoutingDecision(TaskType.COMPLEX_REASONING, mc, "best match");
        assertEquals(TaskType.COMPLEX_REASONING, rd.getTaskType());
        assertEquals("gpt-4", rd.getSelectedModel());
        assertEquals("openai", rd.getSelectedProvider());
        assertEquals(1L, rd.getSelectedModelId());
        assertEquals("best match", rd.getReason());
    }

    @Test
    @DisplayName("addAlternative")
    void testAddAlternative() {
        RoutingDecision rd = new RoutingDecision();
        rd.addAlternative("gpt-3.5");
        rd.addAlternative("gpt-4");
        rd.addAlternative("gpt-4"); // 重复不添加
        assertEquals(2, rd.getAlternatives().size());
    }

    @Test
    @DisplayName("addAlternative null 不添加")
    void testAddAlternativeNull() {
        RoutingDecision rd = new RoutingDecision();
        rd.addAlternative(null);
        assertEquals(0, rd.getAlternatives().size());
    }

    @Test
    @DisplayName("toJson")
    void testToJson() {
        RoutingDecision rd = new RoutingDecision();
        rd.setTaskType(TaskType.SIMPLE_CHAT);
        rd.setSelectedModel("gpt-4");
        rd.setSelectedProvider("openai");
        rd.setReason("default");
        String json = rd.toJson();
        assertTrue(json.contains("SIMPLE_CHAT"));
        assertTrue(json.contains("gpt-4"));
    }

    @Test
    @DisplayName("toString")
    void testToString() {
        RoutingDecision rd = new RoutingDecision();
        rd.setTaskType(TaskType.DEBATE);
        rd.setSelectedModel("gpt-4");
        assertTrue(rd.toString().contains("DEBATE"));
    }
}
