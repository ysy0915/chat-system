package com.example.chat.llm.rag.legacy;

import com.example.chat.llm.rag.legacy.VectorStoreLegacy.ChunkText;
import com.example.chat.llm.rag.legacy.VectorStoreLegacy.SearchResult;
import com.example.chat.storage.VectorStore.VectorHit;
import com.example.chat.storage.VectorStore.VectorRecord;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InMemoryVectorStoreService 单元测试：
 * 纯内存向量库的入库、余弦相似度检索、TopK 截断、collection 名解析与 SPI 适配。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InMemoryVectorStoreService 纯内存向量库")
class InMemoryVectorStoreServiceTest {

    @Mock
    private LegacyEmbeddingService embeddingService;

    private InMemoryVectorStoreService store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStoreService(embeddingService);
        ReflectionTestUtils.setField(store, "collectionPrefix", "kb_");
    }

    @Test
    @DisplayName("空分片不入库、不调用向量化")
    void insertChunks_empty_skips() {
        store.insertChunks(1L, 2L, List.of(), "doc.pdf");
        store.insertChunks(1L, 2L, null, "doc.pdf");

        verify(embeddingService, never()).embedBatch(anyList());
    }

    @Test
    @DisplayName("向量数与分片数不一致时丢弃")
    void insertChunks_sizeMismatch_drops() {
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(new float[]{0.1f}));
        store.insertChunks(1L, 2L, List.of(new ChunkText("a", 0), new ChunkText("b", 1)), "doc.pdf");

        assertEquals(0, store.search(1L, "q", 5).size());
    }

    @Test
    @DisplayName("入库后可按余弦相似度检索并截断 TopK")
    void insertAndSearch_cosineOrder() {
        // 向量 a=(1,0) 与查询 (1,0) 最相似；b=(0,1) 无关
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(
                new float[]{1f, 0f}, new float[]{0f, 1f}));
        store.insertChunks(1L, 2L, List.of(new ChunkText("文本A", 0, 1), new ChunkText("文本B", 1, 2)), "doc.pdf");

        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f, 0f});
        List<SearchResult> hits = store.search(1L, "query", 1);

        assertEquals(1, hits.size());
        assertEquals("文本A", hits.get(0).text);
        assertEquals(2L, hits.get(0).docId);
        assertEquals(1, hits.get(0).page);
        assertEquals("doc.pdf", hits.get(0).source);
        assertTrue(hits.get(0).score > 0.99f);
    }

    @Test
    @DisplayName("查询空白或为空集合返回空")
    void search_blankOrEmpty_returnsEmpty() {
        assertEquals(0, store.search(1L, "  ", 5).size());
        assertEquals(0, store.search(1L, null, 5).size());
        assertEquals(0, store.search(2L, "q", 5).size());
    }

    @Test
    @DisplayName("TopK 为 0 或负数时兜底返回 5 条")
    void search_topKNonPositive_default5() {
        when(embeddingService.embedBatch(anyList())).thenReturn(
                List.of(new float[]{1f}, new float[]{1f}, new float[]{1f}, new float[]{1f},
                        new float[]{1f}, new float[]{1f}, new float[]{1f}));
        store.insertChunks(1L, 2L, List.of(
                new ChunkText("a", 0), new ChunkText("b", 1), new ChunkText("c", 2),
                new ChunkText("d", 3), new ChunkText("e", 4), new ChunkText("f", 5),
                new ChunkText("g", 6)), "doc.pdf");
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f});

        assertEquals(5, store.search(1L, "q", 0).size());
        assertEquals(5, store.search(1L, "q", -1).size());
    }

    @Test
    @DisplayName("dropCollection 清空该集合")
    void dropCollection_clears() {
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(new float[]{1f}));
        store.insertChunks(1L, 2L, List.of(new ChunkText("a", 0)), "doc.pdf");
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f});
        assertEquals(1, store.search(1L, "q", 5).size());

        store.dropCollection(1L);

        assertEquals(0, store.search(1L, "q", 5).size());
        // 其他集合不受影响
        store.insertChunks(3L, 4L, List.of(new ChunkText("b", 0)), "doc2.pdf");
        assertEquals(1, store.search(3L, "q", 5).size());
    }

    @Test
    @DisplayName("name() 返回 memory")
    void name_returnsMemory() {
        assertEquals("memory", store.name());
    }

    @Test
    @DisplayName("SPI：insert/search/drop 按 collection 名路由 kbId")
    void spi_adapter_routesCollection() {
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(new float[]{1f, 0f}, new float[]{0f, 1f}));
        store.insert("kb_7", List.of(
                new VectorRecord("文本A", "doc.pdf", 1f, 0f),
                new VectorRecord("文本B", "doc.pdf", 0f, 1f)));

        // SPI search 直接使用传入向量，不经过 embeddingService
        List<VectorHit> hits = store.search("kb_7", new float[]{1f, 0f}, 1);

        assertEquals(1, hits.size());
        assertEquals("文本A", hits.get(0).text);
        assertEquals(0, store.search("kb_999", new float[]{1f, 0f}, 1).size());

        store.dropCollection("kb_7");
        assertEquals(0, store.search("kb_7", new float[]{1f, 0f}, 1).size());
    }

    @Test
    @DisplayName("SPI：负数/非法 collection 名回退 conversation_memory")
    void spi_conversationMemoryCollection() {
        // 按输入文本数量返回同维度向量，保证两处 insert（各 1 个 chunk）都能入库
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<?> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{1f}).toList();
        });
        store.insert("conversation_memory", List.of(new VectorRecord("记忆", "mem", 1f)));
        store.insert("非法名", List.of(new VectorRecord("记忆2", "mem", 1f)));

        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f});
        assertEquals(2, store.search(-1L, "q", 5).size());
    }

    @Test
    @DisplayName("embedding 异常时检索返回空")
    void search_embeddingThrows_returnsEmpty() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("embed fail"));

        assertEquals(0, store.search(1L, "q", 5).size());
    }
}
