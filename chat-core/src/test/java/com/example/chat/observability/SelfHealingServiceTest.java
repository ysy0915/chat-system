package com.example.chat.observability;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.LLMInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SelfHealingService 真实行为断言（4 种重试策略分支）：
 * 开关关闭/不可重试错误直抛原异常、RATE_LIMIT 换同 provider 模型、
 * AUTH_FAILED 换默认模型、重试用尽抛原异常并记录错误。
 */
@ExtendWith(MockitoExtension.class)
class SelfHealingServiceTest {

    @Mock
    private LLMInvoker llmInvoker;

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private ErrorAggregator errorAggregator;

    private SelfHealingService service;

    private ModelConfig failedConfig;
    private List<LLMMessage> messages;

    @BeforeEach
    void setUp() {
        service = new SelfHealingService();
        ReflectionTestUtils.setField(service, "llmInvoker", llmInvoker);
        ReflectionTestUtils.setField(service, "modelConfigRepository", modelConfigRepository);
        ReflectionTestUtils.setField(service, "errorAggregator", errorAggregator);
        ReflectionTestUtils.setField(service, "autoHealEnabled", true);

        failedConfig = new ModelConfig();
        failedConfig.id = 1L;
        failedConfig.provider = "qwen";
        failedConfig.model = "qwen-turbo";
        messages = List.of(LLMMessage.user("你好"));
    }

    @Test
    void autoHealDisabled_throwsOriginalException() throws Exception {
        ReflectionTestUtils.setField(service, "autoHealEnabled", false);
        Exception original = new RuntimeException("rate limit exceeded");

        Exception thrown = assertThrows(RuntimeException.class,
                () -> service.healAndRetry(failedConfig, messages, 0.7, "chat",
                        "base", "key", original));

        assertSame(original, thrown);
        verify(modelConfigRepository, never()).findAllEnabledByType(anyString());
    }

    @Test
    void unknownError_throwsOriginalWithoutRetry() throws Exception {
        Exception original = new RuntimeException("some random message");

        Exception thrown = assertThrows(RuntimeException.class,
                () -> service.healAndRetry(failedConfig, messages, 0.7, "chat",
                        "base", "key", original));

        assertSame(original, thrown);
        verify(modelConfigRepository, never()).findAllEnabledByType(anyString());
    }

    @Test
    void modelNotFound_throwsOriginalWithoutRetry() throws Exception {
        Exception original = new RuntimeException("model not found");

        Exception thrown = assertThrows(RuntimeException.class,
                () -> service.healAndRetry(failedConfig, messages, 0.7, "chat",
                        "base", "key", original));

        assertSame(original, thrown);
    }

    @Test
    void rateLimit_switchesToAlternateModelOfSameProvider() throws Exception {
        // Arrange
        ModelConfig candidate = new ModelConfig();
        candidate.id = 2L;
        candidate.provider = "qwen";
        candidate.model = "qwen-plus";
        when(modelConfigRepository.findAllEnabledByType("chat")).thenReturn(List.of(failedConfig, candidate));
        when(llmInvoker.invoke(eq(candidate), anyList(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn("alternate answer");

        // Act
        String result = service.healAndRetry(failedConfig, messages, 0.7, "chat",
                "base", "key", new RuntimeException("rate limit exceeded"));

        // Assert
        assertEquals("alternate answer", result);
        verify(llmInvoker).invoke(eq(candidate), anyList(), eq(0.7), anyString(), anyString(), anyString());
    }

    @Test
    void authFailed_usesDefaultModelRegardlessOfProvider() throws Exception {
        // Arrange：候选是不同 provider（deepseek），AUTH_FAILED 策略允许跨 provider 换模型
        ModelConfig candidate = new ModelConfig();
        candidate.id = 3L;
        candidate.provider = "deepseek";
        candidate.model = "deepseek-chat";
        when(modelConfigRepository.findAllEnabledByType("chat")).thenReturn(List.of(failedConfig, candidate));
        when(llmInvoker.invoke(eq(candidate), anyList(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn("default answer");

        // Act
        String result = service.healAndRetry(failedConfig, messages, 0.7, "chat",
                "base", "key", new RuntimeException("401 Unauthorized"));

        // Assert
        assertEquals("default answer", result);
    }

    @Test
    void retriesExhausted_throwsOriginalAndRecordsErrors() throws Exception {
        // Arrange：唯一候选也失败 → 重试 1 次失败后抛原异常
        ModelConfig candidate = new ModelConfig();
        candidate.id = 2L;
        candidate.provider = "qwen";
        candidate.model = "qwen-plus";
        when(modelConfigRepository.findAllEnabledByType("chat")).thenReturn(List.of(failedConfig, candidate));
        when(llmInvoker.invoke(eq(candidate), anyList(), anyDouble(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("still failing"));

        Exception original = new RuntimeException("rate limit exceeded");

        // Act
        Exception thrown = assertThrows(RuntimeException.class,
                () -> service.healAndRetry(failedConfig, messages, 0.7, "chat",
                        "base", "key", original));

        // Assert
        assertSame(original, thrown);
        verify(errorAggregator).recordError(eq("chat"), eq("qwen"), eq("qwen-plus"),
                eq(ErrorType.UNKNOWN), eq("still failing"));
    }
}
