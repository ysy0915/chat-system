package com.example.chat.agent.tool;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.BaseUrlResolver;
import com.example.chat.util.LlmToolExecutor;
import com.example.chat.util.LlmToolInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ToolDispatcher 真实行为断言（ReAct 调度主流程）：
 * 无工具直调 LLM、有工具无 tool_calls 直接返回、tool_calls 执行工具回填后出最终回答、
 * 超 maxToolCalls 强制输出、未知工具返回占位提示。
 */
@ExtendWith(MockitoExtension.class)
class ToolDispatcherTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private LLMInvoker llmInvoker;

    @Mock
    private BaseUrlResolver baseUrlResolver;

    @Mock
    private LlmToolInvoker llmToolInvoker;

    private ToolDispatcher dispatcher;

    private ModelConfig config;
    private List<LLMMessage> messages;

    @BeforeEach
    void setUp() {
        dispatcher = new ToolDispatcher(toolRegistry, llmInvoker, baseUrlResolver, llmToolInvoker);
        ReflectionTestUtils.setField(dispatcher, "maxToolCalls", 3);

        config = new ModelConfig();
        config.id = 1L;
        config.provider = "qwen";
        config.model = "qwen-turbo";
        config.apiKeyEncrypted = "sk-test";
        messages = List.of(LLMMessage.user("现在几点"));
    }

    private Map<String, Object> toolCall(String name) {
        Map<String, Object> tc = new HashMap<>();
        tc.put("function", Map.of("name", name, "arguments", "{}"));
        return tc;
    }

    @Test
    void noToolsRegistered_returnsNullForStreamPath() throws Exception {
        // Arrange
        when(toolRegistry.hasTools()).thenReturn(false);

        // Act
        String result = dispatcher.dispatch("现在几点", config, messages, 0.7, "chat", "base", "sk-test");

        // Assert — 无工具时返回 null，让调用方走流式路径
        assertNull(result);
        verifyNoInteractions(llmToolInvoker);
        verifyNoInteractions(llmInvoker);
    }

    @Test
    void llmWithoutToolCalls_returnsNullForStreamPath() throws Exception {
        // Arrange
        when(toolRegistry.hasTools()).thenReturn(true);
        when(toolRegistry.getToolsSchema()).thenReturn(List.of(Map.of("type", "function")));
        Map<String, Object> llmResp = Map.of("choices", List.of(Map.of("message", Map.of("content", "无需工具"))));
        when(llmToolInvoker.callWithTools(any(), any(), any(), anyList(), anyDouble(), anyList()))
                .thenReturn(llmResp);
        when(llmToolInvoker.extractToolCalls(llmResp)).thenReturn(List.of());
        when(llmToolInvoker.extractContent(llmResp)).thenReturn("无需工具");

        // Act
        String result = dispatcher.dispatch("现在几点", config, messages, 0.7, "chat", "base", "sk-test");

        // Assert — LLM 未触发工具时返回 null，让调用方走流式路径
        assertNull(result);
        verify(llmToolInvoker, never()).executeOneToolCall(any(), any());
        verify(llmInvoker, never()).invoke(any(), anyList(), anyDouble(), anyString(), anyString(), anyString());
    }

    @Test
    void llmWithToolCalls_executesToolThenReturnsFinalAnswer() throws Exception {
        // Arrange
        when(toolRegistry.hasTools()).thenReturn(true);
        when(toolRegistry.getToolsSchema()).thenReturn(List.of(Map.of("type", "function")));
        Map<String, Object> toolCall = toolCall("time");
        Map<String, Object> llmResp = Map.of("choices", List.of(Map.of("message", Map.of("content", ""))));
        when(llmToolInvoker.callWithTools(any(), any(), any(), anyList(), anyDouble(), anyList()))
                .thenReturn(llmResp);
        when(llmToolInvoker.extractToolCalls(llmResp)).thenReturn(List.of(toolCall));
        when(llmToolInvoker.extractContent(llmResp)).thenReturn("");
        when(llmToolInvoker.toolNameOf(toolCall)).thenReturn("time");
        when(llmToolInvoker.executeOneToolCall(eq(toolCall), any(LlmToolExecutor.class))).thenReturn("20:00");
        when(llmInvoker.invoke(eq(config), anyList(), eq(0.7), eq("chat"), eq("base"), eq("sk-test")))
                .thenReturn("当前时间是 20:00");

        // Act
        String result = dispatcher.dispatch("现在几点", config, messages, 0.7, "chat", "base", "sk-test");

        // Assert
        assertEquals("当前时间是 20:00", result);
        verify(llmToolInvoker).executeOneToolCall(eq(toolCall), any(LlmToolExecutor.class));
        verify(llmInvoker).invoke(eq(config), anyList(), eq(0.7), eq("chat"), eq("base"), eq("sk-test"));
    }

    @Test
    void exceedingMaxToolCalls_forcesFinalAnswer() throws Exception {
        // Arrange：maxToolCalls=1，第一轮始终返回 tool_calls → 循环退出走超限路径
        ReflectionTestUtils.setField(dispatcher, "maxToolCalls", 1);
        when(toolRegistry.hasTools()).thenReturn(true);
        when(toolRegistry.getToolsSchema()).thenReturn(List.of(Map.of("type", "function")));
        Map<String, Object> toolCall = toolCall("time");
        Map<String, Object> llmResp = Map.of("choices", List.of(Map.of("message", Map.of("content", ""))));
        when(llmToolInvoker.callWithTools(any(), any(), any(), anyList(), anyDouble(), anyList()))
                .thenReturn(llmResp);
        when(llmToolInvoker.extractToolCalls(llmResp)).thenReturn(List.of(toolCall));
        when(llmToolInvoker.extractContent(llmResp)).thenReturn("");
        when(llmToolInvoker.toolNameOf(toolCall)).thenReturn("time");
        when(llmToolInvoker.executeOneToolCall(eq(toolCall), any(LlmToolExecutor.class))).thenReturn("20:00");
        when(llmInvoker.invoke(eq(config), anyList(), eq(0.7), eq("chat"), eq("base"), eq("sk-test")))
                .thenReturn("超限后最终回答");

        // Act
        String result = dispatcher.dispatch("现在几点", config, messages, 0.7, "chat", "base", "sk-test");

        // Assert
        assertEquals("超限后最终回答", result);
    }

    @Test
    void unknownTool_returnsPlaceholderAndContinues() throws Exception {
        // Arrange：注册中心无 "unknown" 工具 → executeTool 返回占位提示并继续出最终回答
        when(toolRegistry.hasTools()).thenReturn(true);
        when(toolRegistry.getToolsSchema()).thenReturn(List.of(Map.of("type", "function")));
        when(toolRegistry.getTool("unknown")).thenReturn(null);
        Map<String, Object> toolCall = toolCall("unknown");
        Map<String, Object> llmResp = Map.of("choices", List.of(Map.of("message", Map.of("content", ""))));
        when(llmToolInvoker.callWithTools(any(), any(), any(), anyList(), anyDouble(), anyList()))
                .thenReturn(llmResp);
        when(llmToolInvoker.extractToolCalls(llmResp)).thenReturn(List.of(toolCall));
        when(llmToolInvoker.extractContent(llmResp)).thenReturn("");
        when(llmToolInvoker.toolNameOf(toolCall)).thenReturn("unknown");
        when(llmToolInvoker.executeOneToolCall(eq(toolCall), any(LlmToolExecutor.class))).thenAnswer(inv -> {
            LlmToolExecutor executor = inv.getArgument(1);
            return executor.execute("unknown", Map.of());
        });
        when(llmInvoker.invoke(eq(config), anyList(), eq(0.7), eq("chat"), eq("base"), eq("sk-test")))
                .thenReturn("最终回答");

        // Act
        String result = dispatcher.dispatch("测试未知工具", config, messages, 0.7, "chat", "base", "sk-test");

        // Assert
        assertEquals("最终回答", result);
        verify(toolRegistry).getTool("unknown");
    }
}
