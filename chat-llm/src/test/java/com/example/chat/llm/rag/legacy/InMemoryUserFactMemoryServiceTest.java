package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InMemoryUserFactMemoryService 单元测试：
 * 事实抽取-去重-向量化入库、按余弦相似度与阈值召回、TopK 截断与异常降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InMemoryUserFactMemoryService 纯内存用户事实记忆")
class InMemoryUserFactMemoryServiceTest {

    @Mock
    private FactExtractor factExtractor;

    @Mock
    private LegacyEmbeddingService embeddingService;

    private InMemoryUserFactMemoryService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryUserFactMemoryService(factExtractor, embeddingService);
        ReflectionTestUtils.setField(service, "recallTopK", 5);
        ReflectionTestUtils.setField(service, "recallThreshold", 0.35f);
    }

    @Test
    @DisplayName("userId null 或 question 空白时不抽取")
    void saveFacts_invalidInput_skips() {
        service.saveFacts("personal", null, "q", "a");
        service.saveFacts("personal", 1L, null, "a");
        service.saveFacts("personal", 1L, "  ", "a");

        verify(factExtractor, never()).extractFacts(anyString(), anyString());
    }

    @Test
    @DisplayName("无事实抽取结果时不入库")
    void saveFacts_noFacts_skips() {
        when(factExtractor.extractFacts(anyString(), anyString())).thenReturn(List.of());

        service.saveFacts("personal", 1L, "q", "a");

        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    @DisplayName("保存去重事实，重复事实跳过")
    void saveFacts_dedup() {
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenReturn(List.of("用户喜欢咖啡", "用户喜欢茶"));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f, 0f});

        service.saveFacts("personal", 1L, "q", "a");
        service.saveFacts("personal", 1L, "q2", "a2");

        assertEquals(2, service.recallFacts(1L, "咖啡", 5).size());
    }

    @Test
    @DisplayName("向量为空向量时跳过该事实")
    void saveFacts_emptyVector_skips() {
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenReturn(List.of("事实A", "事实B"));
        when(embeddingService.embed("事实A")).thenReturn(new float[]{1f, 0f});
        when(embeddingService.embed("事实B")).thenReturn(new float[0]);
        when(embeddingService.embed("事实")).thenReturn(new float[]{1f, 0f});

        service.saveFacts("personal", 1L, "q", "a");

        assertEquals(1, service.recallFacts(1L, "事实", 5).size());
    }

    @Test
    @DisplayName("单条事实向量化异常不影响其余入库")
    void saveFacts_oneEmbedFails_othersSaved() {
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenReturn(List.of("事实A", "事实B"));
        when(embeddingService.embed("事实A")).thenThrow(new RuntimeException("embed fail"));
        when(embeddingService.embed("事实B")).thenReturn(new float[]{0f, 1f});
        when(embeddingService.embed("事实")).thenReturn(new float[]{0f, 1f});

        service.saveFacts("personal", 1L, "q", "a");

        assertEquals(1, service.recallFacts(1L, "事实", 5).size());
        assertTrue(service.recallFacts(1L, "事实", 5).contains("事实B"));
    }

    @Test
    @DisplayName("抽取异常整体降级")
    void saveFacts_extractThrows_swallowed() {
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenThrow(new RuntimeException("llm down"));

        service.saveFacts("personal", 1L, "q", "a");
        // 不抛异常即通过
    }

    @Test
    @DisplayName("按余弦相似度排序并截断 TopK、过滤低于阈值")
    void recallFacts_sortAndFilter() {
        // 事实A=(1,0)、事实B=(0,1)：查询 (1,0) 时 A 相似 1.0、B 相似 0.0
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenReturn(List.of("事实A", "事实B"));
        when(embeddingService.embed("事实A")).thenReturn(new float[]{1f, 0f});
        when(embeddingService.embed("事实B")).thenReturn(new float[]{0f, 1f});
        service.saveFacts("personal", 1L, "q", "a");

        when(embeddingService.embed("咖啡")).thenReturn(new float[]{1f, 0f});
        List<String> hits = service.recallFacts(1L, "咖啡", 1);

        assertEquals(1, hits.size());
        assertEquals("事实A", hits.get(0));
    }

    @Test
    @DisplayName("阈值过滤：低于 recallThreshold 的事实不返回")
    void recallFacts_thresholdFilter() {
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenReturn(List.of("事实A"));
        when(embeddingService.embed("事实A")).thenReturn(new float[]{1f, 0f});
        service.saveFacts("personal", 1L, "q", "a");

        when(embeddingService.embed("完全无关的问题")).thenReturn(new float[]{0f, 1f});

        assertTrue(service.recallFacts(1L, "完全无关的问题", 5).isEmpty());
    }

    @Test
    @DisplayName("无该用户事实时返回空")
    void recallFacts_noFacts_returnsEmpty() {
        assertEquals(0, service.recallFacts(99L, "q", 5).size());
    }

    @Test
    @DisplayName("查询空白或 userId null 返回空")
    void recallFacts_invalidInput_returnsEmpty() {
        assertEquals(0, service.recallFacts(null, "q", 5).size());
        assertEquals(0, service.recallFacts(1L, null, 5).size());
        assertEquals(0, service.recallFacts(1L, "  ", 5).size());
    }

    @Test
    @DisplayName("topK 为 0/负时使用默认 recallTopK")
    void recallFacts_topKNonPositive_useDefault() {
        when(factExtractor.extractFacts(anyString(), anyString()))
                .thenReturn(List.of("事实1", "事实2", "事实3"));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f});
        service.saveFacts("personal", 1L, "q", "a");

        // 默认 topK=5 但仅有 3 条事实，返回全部
        assertEquals(3, service.recallFacts(1L, "事实", 0).size());
        assertEquals(3, service.recallFacts(1L, "事实", -1).size());
    }

    @Test
    @DisplayName("召回向量化异常返回空")
    void recallFacts_embedThrows_returnsEmpty() {
        when(factExtractor.extractFacts(anyString(), anyString())).thenReturn(List.of("事实A"));
        when(embeddingService.embed("事实A")).thenReturn(new float[]{1f, 0f});
        service.saveFacts("personal", 1L, "q", "a");

        when(embeddingService.embed("q")).thenThrow(new RuntimeException("embed fail"));

        assertEquals(0, service.recallFacts(1L, "q", 5).size());
    }

    @Test
    @DisplayName("不同用户事实相互隔离")
    void factsIsolatedByUser() {
        when(factExtractor.extractFacts(anyString(), anyString())).thenReturn(List.of("用户A事实"));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f});
        service.saveFacts("personal", 1L, "q", "a");

        when(factExtractor.extractFacts(anyString(), anyString())).thenReturn(List.of("用户B事实"));
        service.saveFacts("personal", 2L, "q", "a");

        assertEquals(0, service.recallFacts(3L, "q", 5).size());
        assertTrue(service.recallFacts(1L, "q", 5).contains("用户A事实"));
        assertTrue(service.recallFacts(2L, "q", 5).contains("用户B事实"));
    }
}
