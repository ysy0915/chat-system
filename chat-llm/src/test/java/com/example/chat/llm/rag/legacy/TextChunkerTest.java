package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextChunker 单元测试
 */
@DisplayName("TextChunker 文本分片器")
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    @DisplayName("空文本返回空列表")
    void chunk_empty_returnsEmpty() {
        assertTrue(chunker.chunk("").isEmpty());
        assertTrue(chunker.chunk(null).isEmpty());
        assertTrue(chunker.chunk("   ").isEmpty());
    }

    @Test
    @DisplayName("短文本返回单个分片")
    void chunk_shortText_singleChunk() {
        List<LegacyVectorStoreService.ChunkText> chunks = chunker.chunk("这是一段短文本。");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text.contains("短文本"));
        assertEquals(0, chunks.get(0).chunkIndex);
    }

    @Test
    @DisplayName("按句子边界切分多个分片")
    void chunk_multipleSentences_multipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句话，内容比较长用于测试分片功能。");
        }
        List<LegacyVectorStoreService.ChunkText> chunks = chunker.chunk(sb.toString(), 100, 20);
        assertTrue(chunks.size() > 1);
        // 验证索引递增
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex);
        }
    }

    @Test
    @DisplayName("单句超过 chunkSize 硬切")
    void chunk_longSentence_hardCut() {
        String longSentence = "这是一个很长的句子".repeat(100);
        List<LegacyVectorStoreService.ChunkText> chunks = chunker.chunk(longSentence, 50, 10);
        assertTrue(chunks.size() > 1);
        for (LegacyVectorStoreService.ChunkText c : chunks) {
            assertTrue(c.text.length() <= 50 + 10);
        }
    }

    @Test
    @DisplayName("换行符也作为句子边界")
    void chunk_newlineAsBoundary() {
        String text = "第一行内容\n第二行内容\n第三行内容";
        List<LegacyVectorStoreService.ChunkText> chunks = chunker.chunk(text, 500, 50);
        assertFalse(chunks.isEmpty());
    }

    @Test
    @DisplayName("默认参数分片（chunkSize=500, overlap=50）")
    void chunk_defaultParams() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("测试句子").append(i).append("。");
        }
        List<LegacyVectorStoreService.ChunkText> chunks = chunker.chunk(sb.toString());
        assertFalse(chunks.isEmpty());
        // 每块不超过 500 + overlap
        for (LegacyVectorStoreService.ChunkText c : chunks) {
            assertTrue(c.text.length() <= 550);
        }
    }
}
