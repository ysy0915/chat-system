package com.example.chat.service;

import com.example.chat.client.RagClient;
import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ChatRagEnhancer 单元测试
 */
@DisplayName("ChatRagEnhancer 对话自动 RAG 增强器")
class ChatRagEnhancerTest {

    private ChatRagEnhancer enhancer;
    private RagClient ragClient;

    @BeforeEach
    void setUp() throws Exception {
        ragClient = mock(RagClient.class);
        enhancer = new ChatRagEnhancer(ragClient);
        setField(enhancer, "chatRagEnabled", true);
        setField(enhancer, "chatRagKbId", 1L);
        setField(enhancer, "chatRagTopK", 3);
        setField(enhancer, "chatRagScoreThreshold", 0.3f);
        setField(enhancer, "chatRagMaxChars", 2000);
    }

    private void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private IntentResult intent(IntentCategory c) {
        return Mockito.mock(IntentResult.class);
    }

    @Test
    @DisplayName("KNOWLEDGE_QA 意图 + 非实时问题 → 需要检索")
    void shouldAutoRag_knowledgeQa_returnsTrue() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.KNOWLEDGE_QA);
        assertTrue(enhancer.shouldAutoRag(intent, "什么是机器学习"));
    }

    @Test
    @DisplayName("TASK_EXECUTION 意图 + 非实时问题 → 需要检索")
    void shouldAutoRag_taskExecution_returnsTrue() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.TASK_EXECUTION);
        assertTrue(enhancer.shouldAutoRag(intent, "如何配置 Nginx 反向代理"));
    }

    @Test
    @DisplayName("闲聊意图 → 不检索")
    void shouldAutoRag_chat_returnsFalse() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.GENERAL_CHAT);
        assertFalse(enhancer.shouldAutoRag(intent, "你好"));
    }

    @Test
    @DisplayName("null 意图 → 不检索")
    void shouldAutoRag_nullIntent_returnsFalse() {
        assertFalse(enhancer.shouldAutoRag(null, "什么是 AI"));
    }

    @Test
    @DisplayName("天气类实时问题 → 不检索")
    void shouldAutoRag_weatherQuery_returnsFalse() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.KNOWLEDGE_QA);
        assertFalse(enhancer.shouldAutoRag(intent, "今天天气怎么样"));
    }

    @Test
    @DisplayName("时间类实时问题 → 不检索")
    void shouldAutoRag_timeQuery_returnsFalse() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.KNOWLEDGE_QA);
        assertFalse(enhancer.shouldAutoRag(intent, "现在几点了"));
    }

    @Test
    @DisplayName("股票行情类实时问题 → 不检索")
    void shouldAutoRag_stockQuery_returnsFalse() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.TASK_EXECUTION);
        assertFalse(enhancer.shouldAutoRag(intent, "今天股票行情如何"));
    }

    @Test
    @DisplayName("个人数据类问题 → 不检索")
    void shouldAutoRag_personalQuery_returnsFalse() {
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.KNOWLEDGE_QA);
        assertFalse(enhancer.shouldAutoRag(intent, "我的订单状态"));
    }

    @Test
    @DisplayName("开关关闭 → 不检索")
    void shouldAutoRag_disabled_returnsFalse() throws Exception {
        setField(enhancer, "chatRagEnabled", false);
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.KNOWLEDGE_QA);
        assertFalse(enhancer.shouldAutoRag(intent, "什么是 AI"));
    }

    @Test
    @DisplayName("kbId 未配置 → 不检索")
    void shouldAutoRag_noKbId_returnsFalse() throws Exception {
        setField(enhancer, "chatRagKbId", 0L);
        IntentResult intent = mock(IntentResult.class);
        when(intent.category()).thenReturn(IntentCategory.KNOWLEDGE_QA);
        assertFalse(enhancer.shouldAutoRag(intent, "什么是 AI"));
    }

    @Test
    @DisplayName("isRealTimeOrPersonalQuery 天气关键词命中")
    void isRealTime_weather_matched() {
        assertTrue(enhancer.isRealTimeOrPersonalQuery("明天的天气怎么样"));
        assertTrue(enhancer.isRealTimeOrPersonalQuery("今天多少度"));
    }

    @Test
    @DisplayName("isRealTimeOrPersonalQuery 普通问题不命中")
    void isRealTime_normal_notMatched() {
        assertFalse(enhancer.isRealTimeOrPersonalQuery("什么是机器学习"));
        assertFalse(enhancer.isRealTimeOrPersonalQuery("如何学习编程"));
    }

    @Test
    @DisplayName("isRealTimeOrPersonalQuery 空输入不命中")
    void isRealTime_empty_notMatched() {
        assertFalse(enhancer.isRealTimeOrPersonalQuery(""));
        assertFalse(enhancer.isRealTimeOrPersonalQuery(null));
    }

    @Test
    @DisplayName("buildContext 有命中返回非空")
    void buildContext_withResults_returnsContext() {
        List<RagClient.SearchResult> results = List.of(
                new RagClient.SearchResult("机器学习是人工智能的一个分支", "source1", 1L, 0.85f),
                new RagClient.SearchResult("深度学习是机器学习的子集", "source2", 2L, 0.6f)
        );
        when(ragClient.search(1L, "什么是机器学习", 3)).thenReturn(results);
        String ctx = enhancer.buildContext("什么是机器学习");
        assertNotNull(ctx);
        assertTrue(ctx.contains("机器学习"));
        assertTrue(ctx.contains("相似度"));
    }

    @Test
    @DisplayName("buildContext 低分结果被过滤")
    void buildContext_lowScore_filtered() {
        List<RagClient.SearchResult> results = List.of(
                new RagClient.SearchResult("不相关内容", "src", 1L, 0.1f)
        );
        when(ragClient.search(anyLong(), anyString(), anyInt())).thenReturn(results);
        String ctx = enhancer.buildContext("什么是机器学习");
        assertNull(ctx);
    }

    @Test
    @DisplayName("buildContext 检索异常返回 null 不影响主流程")
    void buildContext_exception_returnsNull() {
        when(ragClient.search(anyLong(), anyString(), anyInt())).thenThrow(new RuntimeException("connection refused"));
        String ctx = enhancer.buildContext("什么是机器学习");
        assertNull(ctx);
    }

    @Test
    @DisplayName("buildContext 空结果返回 null")
    void buildContext_emptyResults_returnsNull() {
        when(ragClient.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        assertNull(enhancer.buildContext("什么是机器学习"));
    }

    @Test
    @DisplayName("buildSystemPrompt 包含参考资料")
    void buildSystemPrompt_containsContext() {
        String prompt = enhancer.buildSystemPrompt("这是参考资料内容");
        assertTrue(prompt.contains("参考资料"));
        assertTrue(prompt.contains("这是参考资料内容"));
    }
}
