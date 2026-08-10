package com.example.chat.service;

import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatProcessor 关键逻辑测试
 * <p>
 * 使用反射测试 private 方法 isComplexIntent()，
 * 验证意图判定 → 思考链启用的映射逻辑。
 */
@DisplayName("ChatProcessor 意图→思考链映射")
class ChatProcessorTest {

    /**
     * 复杂意图应启用思考链：REASONING / CODE_GENERATION / KNOWLEDGE_QA / TASK_EXECUTION
     */
    @ParameterizedTest
    @EnumSource(value = IntentCategory.class, names = {
            "REASONING", "CODE_GENERATION", "KNOWLEDGE_QA", "TASK_EXECUTION"
    })
    @DisplayName("复杂意图 → isComplexIntent = true")
    void shouldReturnTrueForComplexIntents(IntentCategory category) throws Exception {
        IntentResult result = new IntentResult(category, 0.9, "L2", "");
        boolean isComplex = invokeIsComplexIntent(result);
        assertTrue(isComplex, category + " 应判定为复杂意图");
    }

    /**
     * 简单意图不应启用思考链
     */
    @ParameterizedTest
    @EnumSource(value = IntentCategory.class, names = {
            "GENERAL_CHAT", "EMOTIONAL_SUPPORT", "CREATIVE_WRITING",
            "SUMMARIZATION", "TRANSLATION", "UNKNOWN"
    })
    @DisplayName("简单意图 → isComplexIntent = false")
    void shouldReturnFalseForSimpleIntents(IntentCategory category) throws Exception {
        IntentResult result = new IntentResult(category, 0.8, "L1", "");
        boolean isComplex = invokeIsComplexIntent(result);
        assertFalse(isComplex, category + " 不应判定为复杂意图");
    }

    @Test
    @DisplayName("null 意图 → isComplexIntent = false（防御性）")
    void shouldReturnFalseForNullIntent() throws Exception {
        boolean isComplex = invokeIsComplexIntent(null);
        assertFalse(isComplex, "null 意图应安全返回 false");
    }

    @Test
    @DisplayName("低置信度 REASONING 仍触发思考链")
    void shouldTriggerThinkingEvenWithLowConfidence() throws Exception {
        IntentResult result = new IntentResult(IntentCategory.REASONING, 0.3, "L2", "");
        boolean isComplex = invokeIsComplexIntent(result);
        assertTrue(isComplex, "低置信度 REASONING 仍应启用思考链");
    }

    // ==================== 反射工具方法 ====================

    /**
     * 用反射调用 ChatProcessor.isComplexIntent(IntentResult)
     */
    private boolean invokeIsComplexIntent(IntentResult result) throws Exception {
        Method method = ChatProcessor.class.getDeclaredMethod("isComplexIntent", IntentResult.class);
        method.setAccessible(true);
        ChatProcessor dummy = new ChatProcessor(
                null, null, null, null, null, null, null, null, null, null, null);
        return (boolean) method.invoke(dummy, result);
    }
}
