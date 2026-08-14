package com.example.chat.llm.rag.legacy;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分片器：将长文档切分为适合 Embedding 的小块
 *
 * 策略：按句子边界切分 + 滑动窗口重叠
 * - chunkSize：每块最大字符数（默认 500，约 250 汉字）
 * - overlap：相邻块的重叠字符数（默认 50，保证上下文连续性）
 */
@Service("legacyTextChunker")
public class TextChunker {

    private static final int DEFAULT_CHUNK_SIZE = 500;
    private static final int DEFAULT_OVERLAP = 50;

    /**
     * 按默认参数分片
     */
    public List<VectorStoreLegacy.ChunkText> chunk(String text) {
        return chunk(text, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * 按指定参数分片
     * @param chunkSize 每块最大字符数
     * @param overlap 重叠字符数
     */
    public List<VectorStoreLegacy.ChunkText> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 按句子边界切分（中文句号、问号、感叹号、换行；英文句号+空格）
        String[] sentences = text.split("(?<=[。！？\n])|(?<=\\.\\s)");

        List<VectorStoreLegacy.ChunkText> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) continue;

            // 单句就超过 chunkSize：硬切
            if (trimmed.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(new VectorStoreLegacy.ChunkText(current.toString(), chunkIndex++));
                    current = new StringBuilder();
                }
                for (int i = 0; i < trimmed.length(); i += chunkSize - overlap) {
                    int end = Math.min(i + chunkSize, trimmed.length());
                    chunks.add(new VectorStoreLegacy.ChunkText(trimmed.substring(i, end), chunkIndex++));
                }
                continue;
            }

            // 累积到 chunkSize
            if (current.length() + trimmed.length() > chunkSize) {
                chunks.add(new VectorStoreLegacy.ChunkText(current.toString(), chunkIndex++));
                // 保留 overlap 长度的上下文
                String prev = current.toString();
                int start = Math.max(0, prev.length() - overlap);
                current = new StringBuilder(prev.substring(start));
            }
            current.append(trimmed);
        }

        if (current.length() > 0) {
            chunks.add(new VectorStoreLegacy.ChunkText(current.toString(), chunkIndex));
        }

        return chunks;
    }
}
