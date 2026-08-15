package com.example.chat.llm.rag.hybrid;

import com.example.chat.llm.rag.legacy.KeywordSearchService;
import com.example.chat.llm.rag.legacy.VectorStoreLegacy;
import com.example.chat.llm.rag.rerank.RerankService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HybridSearchService 单元测试：
 * 纯向量检索、关键词通道 RRF 融合、keyword/rerank 失败降级、topK 截断与参数兜底。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HybridSearchService 混合检索编排")
class HybridSearchServiceTest {

    @Mock
    private VectorStoreLegacy vectorStoreService;

    @Mock
    private KeywordSearchService keywordSearchService;

    @Mock
    private RerankService rerankService;

    private HybridSearchService service;

    @BeforeEach
    void setUp() {
        service = new HybridSearchService(vectorStoreService, keywordSearchService, rerankService);
        ReflectionTestUtils.setField(service, "keywordEnabled", false);
        ReflectionTestUtils.setField(service, "recallFactor", 3);
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "rerankEnabled", false);
    }

    private static VectorStoreLegacy.SearchResult hit(String text, long docId, float score, int page) {
        return new VectorStoreLegacy.SearchResult(text, "doc-" + docId + ".pdf", docId, score, page);
    }

    @Test
    @DisplayName("向量存储未启用时返回空列表")
    void search_vectorStoreNull_returnsEmpty() {
        HybridSearchService noVector = new HybridSearchService(null, keywordSearchService, rerankService);

        List<VectorStoreLegacy.SearchResult> out = noVector.search(1L, "query", 5);

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("仅向量召回：放大召回 topK 后截断到最终 topK")
    void search_pureVector_truncatesToTopK() {
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1),
                hit("b", 2, 0.90f, 1),
                hit("c", 3, 0.80f, 1),
                hit("d", 4, 0.70f, 1)));

        List<VectorStoreLegacy.SearchResult> out = service.search(1L, "query", 2);

        assertEquals(2, out.size());
        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
        // 召回放大系数 3 → 召回 6 条，最终截断 2 条
        verify(vectorStoreService).search(1L, "query", 6);
    }

    @Test
    @DisplayName("关键词通道启用：两路 RRF 融合，双路命中者排序提前")
    void search_keywordEnabled_fusesByRRF() {
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        // RRF 合并键为 docId:page:text，双路命中须文本一致才会累加
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("text-a", 1, 0.95f, 1),
                hit("shared", 2, 0.90f, 1)));
        when(keywordSearchService.keywordSearch(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("shared", 2, 0.8f, 1),
                hit("text-c", 3, 0.7f, 1)));

        List<VectorStoreLegacy.SearchResult> out = service.search(1L, "query", 3);

        // doc2 在向量与关键词两路均命中（1/61+1/62），RRF 累加后应排第一
        assertEquals(3, out.size());
        assertEquals(2, out.get(0).docId);
        assertEquals("shared", out.get(0).text);
        assertEquals(1, out.get(1).docId);
        assertEquals(3, out.get(2).docId);
    }

    @Test
    @DisplayName("关键词通道抛异常时降级为纯向量结果")
    void search_keywordThrows_fallsBackToVector() {
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1)));
        when(keywordSearchService.keywordSearch(anyLong(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        List<VectorStoreLegacy.SearchResult> out = service.search(1L, "query", 3);

        assertEquals(1, out.size());
        assertEquals("a", out.get(0).text);
    }

    @Test
    @DisplayName("重排启用时返回重排后的结果")
    void search_rerankEnabled_returnsReranked() {
        ReflectionTestUtils.setField(service, "rerankEnabled", true);
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1),
                hit("b", 2, 0.90f, 1),
                hit("c", 3, 0.80f, 1)));
        when(rerankService.rerank(anyString(), any(), anyInt())).thenReturn(List.of(
                hit("c", 3, 0.99f, 1),
                hit("b", 2, 0.60f, 1)));

        List<VectorStoreLegacy.SearchResult> out = service.search(1L, "query", 2);

        assertEquals(2, out.size());
        assertEquals("c", out.get(0).text);
        assertEquals("b", out.get(1).text);
        verify(rerankService).rerank(org.mockito.ArgumentMatchers.eq("query"), any(),
                org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    @DisplayName("重排抛异常时降级为融合后的原序")
    void search_rerankThrows_fallsBackToFused() {
        ReflectionTestUtils.setField(service, "rerankEnabled", true);
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1),
                hit("b", 2, 0.90f, 1)));
        when(rerankService.rerank(anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("rerank timeout"));

        List<VectorStoreLegacy.SearchResult> out = service.search(1L, "query", 3);

        assertEquals(2, out.size());
        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
    }

    @Test
    @DisplayName("重排未启用时不调用 rerankService")
    void search_rerankDisabled_notInvoked() {
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1)));

        service.search(1L, "query", 2);

        verify(rerankService, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("topK 为 0 或负数时兜底返回至少 1 条")
    void search_topKZeroOrNegative_atLeastOne() {
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1),
                hit("b", 2, 0.90f, 1)));

        List<VectorStoreLegacy.SearchResult> zero = service.search(1L, "q", 0);
        List<VectorStoreLegacy.SearchResult> negative = service.search(1L, "q", -3);

        assertEquals(1, zero.size());
        assertEquals(1, negative.size());
    }

    @Test
    @DisplayName("recallFactor 为 0 时兜底按 k+2 召回")
    void search_recallFactorZero_recallsKPlus2() {
        ReflectionTestUtils.setField(service, "recallFactor", 0);
        when(vectorStoreService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit("a", 1, 0.95f, 1)));

        service.search(1L, "q", 5);

        verify(vectorStoreService).search(1L, "q", 7);
    }
}
