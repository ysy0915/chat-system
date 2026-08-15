package com.example.chat.llm.rag.legacy;

import com.example.chat.llm.rag.legacy.RagChunkRepository.RagChunkRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KeywordSearchService 单元测试：
 * 开关未启用/空白 query 短路、得分归一化 sigmoid 映射、仓储异常降级、
 * 分片入库与删除的联动与异常吞掉、启动建表 DDL 幂等。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeywordSearchService 关键词检索")
class KeywordSearchServiceTest {

    @Mock
    private RagChunkRepository ragChunkRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private KeywordSearchService service;

    @BeforeEach
    void setUp() {
        service = new KeywordSearchService(ragChunkRepository, jdbcTemplate);
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
    }

    private static RagChunkRow row(Long docId, Integer page, String text, String source, Double score) {
        RagChunkRow row = new RagChunkRow();
        row.docId = docId;
        row.page = page;
        row.text = text;
        row.source = source;
        row.score = score;
        return row;
    }

    @Test
    @DisplayName("开关未启用时不查询仓储并返回空")
    void keywordSearch_disabled_returnsEmpty() {
        ReflectionTestUtils.setField(service, "keywordEnabled", false);

        List<VectorStoreLegacy.SearchResult> out = service.keywordSearch(1L, "query", 5);

        assertTrue(out.isEmpty());
        verify(ragChunkRepository, never()).keywordSearch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("query 为 null 或空白时返回空")
    void keywordSearch_blankQuery_returnsEmpty() {
        assertTrue(service.keywordSearch(1L, null, 5).isEmpty());
        assertTrue(service.keywordSearch(1L, "   ", 5).isEmpty());
        verify(ragChunkRepository, never()).keywordSearch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("命中行按 sigmoid 归一化并映射字段")
    void keywordSearch_hits_normalizeAndMap() {
        when(ragChunkRepository.keywordSearch(1L, "query", 5)).thenReturn(List.of(
                row(10L, 2, "文本A", "doc.pdf", 1.0),
                row(null, null, "文本B", null, 3.0),
                row(12L, 0, null, "doc2.pdf", null)));

        List<VectorStoreLegacy.SearchResult> out = service.keywordSearch(1L, " query ", 5);

        assertEquals(3, out.size());
        // 1.0/(1.0+1.0)=0.5；3.0/(3.0+1.0)=0.75；null score → 0
        assertEquals(0.5f, out.get(0).score, 0.0001f);
        assertEquals(0.75f, out.get(1).score, 0.0001f);
        assertEquals(0.0f, out.get(2).score, 0.0001f);
        // docId/page/source/text 空值兜底
        assertEquals(-1L, out.get(1).docId);
        assertEquals(0, out.get(1).page);
        assertEquals("", out.get(1).source);
        assertEquals("", out.get(2).text);
        // 非空字段透传
        assertEquals(10L, out.get(0).docId);
        assertEquals(2, out.get(0).page);
        assertEquals("doc.pdf", out.get(0).source);
        assertEquals("文本A", out.get(0).text);
    }

    @Test
    @DisplayName("仓储检索抛异常时降级返回空")
    void keywordSearch_repositoryThrows_returnsEmpty() {
        when(ragChunkRepository.keywordSearch(1L, "query", 5))
                .thenThrow(new RuntimeException("mysql down"));

        List<VectorStoreLegacy.SearchResult> out = service.keywordSearch(1L, "query", 5);

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("开关未启用或分片为空时不写入")
    void insertChunks_disabledOrEmpty_skips() {
        ReflectionTestUtils.setField(service, "keywordEnabled", false);
        service.insertChunks(1L, 2L, List.of(new VectorStoreLegacy.ChunkText("t", 0, 1)), "doc.pdf");
        verify(ragChunkRepository, never()).insertBatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        service.insertChunks(1L, 2L, List.of(), "doc.pdf");
        service.insertChunks(1L, 2L, null, "doc.pdf");
        verify(ragChunkRepository, never()).insertBatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("分片入库正常调用仓储")
    void insertChunks_callsRepository() {
        List<VectorStoreLegacy.ChunkText> chunks = List.of(
                new VectorStoreLegacy.ChunkText("文本1", 0, 1),
                new VectorStoreLegacy.ChunkText("文本2", 1, 2));

        service.insertChunks(10L, 20L, chunks, "doc.pdf");

        verify(ragChunkRepository).insertBatch(10L, 20L, "doc.pdf", chunks);
    }

    @Test
    @DisplayName("分片入库仓储异常时吞掉不抛")
    void insertChunks_repositoryThrows_swallowed() {
        when(ragChunkRepository.insertBatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db error"));

        service.insertChunks(1L, 2L, List.of(new VectorStoreLegacy.ChunkText("t", 0, 1)), "doc.pdf");
        // 不抛异常即通过
    }

    @Test
    @DisplayName("删除文档/知识库：开关关闭跳过，异常吞掉")
    void delete_swallowsAndSkips() {
        ReflectionTestUtils.setField(service, "keywordEnabled", false);
        service.deleteByDoc(1L);
        service.deleteByKb(1L);
        verify(ragChunkRepository, never()).deleteByDoc(org.mockito.ArgumentMatchers.any());
        verify(ragChunkRepository, never()).deleteByKb(org.mockito.ArgumentMatchers.any());

        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        service.deleteByDoc(1L);
        service.deleteByKb(2L);
        verify(ragChunkRepository).deleteByDoc(1L);
        verify(ragChunkRepository).deleteByKb(2L);

        when(ragChunkRepository.deleteByDoc(3L)).thenThrow(new RuntimeException("db error"));
        service.deleteByDoc(3L);
        when(ragChunkRepository.deleteByKb(4L)).thenThrow(new RuntimeException("db error"));
        service.deleteByKb(4L);
        // 异常被吞掉，不抛即通过
    }

    @Test
    @DisplayName("init：开关关闭或 jdbcTemplate 为空时不执行 DDL")
    void init_disabledOrNoTemplate_skipsDdl() {
        ReflectionTestUtils.setField(service, "keywordEnabled", false);
        service.init();
        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());

        KeywordSearchService noTemplate = new KeywordSearchService(ragChunkRepository, null);
        ReflectionTestUtils.setField(noTemplate, "keywordEnabled", true);
        noTemplate.init();
        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("init：启用时执行幂等建表 DDL")
    void init_enabled_executesDdl() {
        service.init();
        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("CREATE TABLE IF NOT EXISTS rag_chunks"));
    }

    @Test
    @DisplayName("init：DDL 执行异常被吞掉不抛")
    void init_ddlThrows_swallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("no permission"))
                .when(jdbcTemplate).execute(org.mockito.ArgumentMatchers.anyString());
        service.init();
        // 不抛异常即通过
    }
}
