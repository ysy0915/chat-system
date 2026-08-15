package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * InMemoryRAGRepository 单元测试：
 * 知识库/文档 CRUD、级联删除、状态更新与统计聚合。
 */
@DisplayName("InMemoryRAGRepository 纯内存知识库仓储")
class InMemoryRAGRepositoryTest {

    private InMemoryRAGRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRAGRepository();
    }

    @Test
    @DisplayName("插入知识库自动分配 id 与创建时间")
    void insertKb_assignsIdAndTime() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.name = "测试库";

        assertEquals(1, repo.insertKnowledgeBase(kb));
        assertNotNull(kb.id);
        assertNotNull(kb.createdAt);
        assertNotNull(repo.findKnowledgeBaseById(kb.id));
    }

    @Test
    @DisplayName("查询列表按 id 降序")
    void findAllSortedDesc() {
        KnowledgeBase a = new KnowledgeBase();
        KnowledgeBase b = new KnowledgeBase();
        repo.insertKnowledgeBase(a);
        repo.insertKnowledgeBase(b);

        List<KnowledgeBase> all = repo.findAllKnowledgeBases();

        assertEquals(2, all.size());
        assertEquals(b.id, all.get(0).id);
        assertEquals(a.id, all.get(1).id);
    }

    @Test
    @DisplayName("删除知识库级联删除文档，不存在返回 0")
    void deleteKb_cascades() {
        KnowledgeBase kb = new KnowledgeBase();
        repo.insertKnowledgeBase(kb);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.knowledgeBaseId = kb.id;
        repo.insertDocument(doc);

        assertEquals(1, repo.deleteKnowledgeBase(kb.id));

        assertNull(repo.findKnowledgeBaseById(kb.id));
        assertEquals(0, repo.countDocumentsByKbId(kb.id));
        assertEquals(0, repo.deleteKnowledgeBase(999L));
    }

    @Test
    @DisplayName("更新知识库统计")
    void updateStats() {
        KnowledgeBase kb = new KnowledgeBase();
        repo.insertKnowledgeBase(kb);

        assertEquals(1, repo.updateKnowledgeBaseStats(kb.id, 3, 20));
        assertEquals(3, repo.findKnowledgeBaseById(kb.id).documentCount);
        assertEquals(20, repo.findKnowledgeBaseById(kb.id).totalChunks);
        assertEquals(0, repo.updateKnowledgeBaseStats(999L, 1, 1));
    }

    @Test
    @DisplayName("文档插入/查询/状态更新/删除")
    void documentLifecycle() {
        KnowledgeDocument d1 = new KnowledgeDocument();
        d1.knowledgeBaseId = 1L;
        KnowledgeDocument d2 = new KnowledgeDocument();
        d2.knowledgeBaseId = 1L;
        repo.insertDocument(d1);
        repo.insertDocument(d2);

        assertEquals(2, repo.countDocumentsByKbId(1L));
        List<KnowledgeDocument> docs = repo.findDocumentsByKbId(1L);
        assertEquals(2, docs.size());
        assertEquals(d2.id, docs.get(0).id); // 降序

        assertEquals(1, repo.updateDocumentStatus(d1.id, "done", null, 10));
        assertEquals("done", repo.findDocumentsByKbId(1L).stream()
                .filter(d -> d.id.equals(d1.id)).findFirst().orElseThrow().status);
        assertEquals(10, repo.findDocumentsByKbId(1L).stream()
                .filter(d -> d.id.equals(d1.id)).findFirst().orElseThrow().chunkCount);

        assertEquals(1, repo.deleteDocument(d1.id));
        assertEquals(1, repo.countDocumentsByKbId(1L));
        assertEquals(0, repo.deleteDocument(999L));
        assertEquals(0, repo.updateDocumentStatus(999L, "done", null, 0));
    }

    @Test
    @DisplayName("统计分片数仅计入 done 状态")
    void sumChunksOnlyDone() {
        KnowledgeDocument done = new KnowledgeDocument();
        done.knowledgeBaseId = 1L;
        done.status = "done";
        done.chunkCount = 5;
        KnowledgeDocument processing = new KnowledgeDocument();
        processing.knowledgeBaseId = 1L;
        processing.status = "processing";
        processing.chunkCount = 100;
        KnowledgeDocument otherKb = new KnowledgeDocument();
        otherKb.knowledgeBaseId = 2L;
        otherKb.status = "done";
        otherKb.chunkCount = 7;
        repo.insertDocument(done);
        repo.insertDocument(processing);
        repo.insertDocument(otherKb);

        assertEquals(5, repo.sumChunksByKbId(1L));
        assertEquals(7, repo.sumChunksByKbId(2L));
    }
}
