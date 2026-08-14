package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.exception.LLMCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DirectLLMClient 单元测试
 *
 * 不测试真实 HTTP 调用（需 mock HttpClient），仅测试参数校验和异常分支
 */
@DisplayName("DirectLLMClient 直连 LLM 客户端")
class DirectLLMClientTest {

    private DirectLLMClient client;

    @BeforeEach
    void setUp() {
        client = new DirectLLMClient(new ObjectMapper());
    }

    @Test
    @DisplayName("API Key 为空抛出 LLMCallException")
    void call_emptyApiKey_throwsException() {
        LLMCallException ex = assertThrows(LLMCallException.class, () ->
                client.call("https://api.example.com/v1", "", "model",
                        List.of(LLMMessage.user("test")), 0.7, -1));
        assertTrue(ex.getMessage().contains("API Key"));
    }

    @Test
    @DisplayName("API Key 为 null 抛出 LLMCallException")
    void call_nullApiKey_throwsException() {
        assertThrows(LLMCallException.class, () ->
                client.call("https://api.example.com/v1", null, "model",
                        List.of(LLMMessage.user("test")), 0.7, -1));
    }

    @Test
    @DisplayName("API Key 为空白字符串抛出 LLMCallException")
    void call_blankApiKey_throwsException() {
        assertThrows(LLMCallException.class, () ->
                client.call("https://api.example.com/v1", "   ", "model",
                        List.of(LLMMessage.user("test")), 0.7, -1));
    }
}
