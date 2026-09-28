package com.example.chat.llm.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MilvusKnowledgeGraphService} 内容指纹 PK 单测——同名/同三元组必须得到
 * 相同 id（Milvus upsert 幂等去重的正确性前提）。
 */
class MilvusKnowledgeGraphServiceTest {

    @Test
    void fingerprintStableForSameContent() {
        assertEquals(MilvusKnowledgeGraphService.fingerprint("Java"),
                MilvusKnowledgeGraphService.fingerprint("Java"));
        assertEquals(MilvusKnowledgeGraphService.fingerprint("Milvus|存储|向量数据库"),
                MilvusKnowledgeGraphService.fingerprint("Milvus|存储|向量数据库"));
    }

    @Test
    void fingerprintDiffersForDifferentContent() {
        assertTrue(MilvusKnowledgeGraphService.fingerprint("Java")
                != MilvusKnowledgeGraphService.fingerprint("JavaScript"));
        assertTrue(MilvusKnowledgeGraphService.fingerprint("A|关系|B")
                != MilvusKnowledgeGraphService.fingerprint("B|关系|A"));
    }

    @Test
    void fingerprintAlwaysNonNegative() {
        // Int64 PK 必须非负，负数会 upsert 失败
        assertTrue(MilvusKnowledgeGraphService.fingerprint("") >= 0);
        assertTrue(MilvusKnowledgeGraphService.fingerprint("任意中文内容😀") >= 0);
        assertTrue(MilvusKnowledgeGraphService.fingerprint("x".repeat(1000)) >= 0);
    }
}
