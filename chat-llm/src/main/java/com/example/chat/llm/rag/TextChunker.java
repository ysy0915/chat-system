package com.example.chat.llm.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器 — 按句子切分并保持上下文重叠。
 */
@Component
public class TextChunker {

    /**
     * 将文本切分成多个块。
     *
     * @param text    原始文本
     * @param size    每块最大字符数 (默认 500)
     * @param overlap 重叠字符数 (默认 50)
     */
    public List<String> chunk(String text, int size, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();

        // 按段落切分 (双换行)
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() + 1 > size && current.length() > 0) {
                chunks.add(current.toString().trim());
                // 重叠：保留上一块的末尾
                String overlapText = current.substring(
                        Math.max(0, current.length() - overlap));
                current = new StringBuilder(overlapText);
            }

            if (current.length() > 0) current.append("\n\n");
            current.append(trimmed);

            // 如果当前段落本身太长，按句子再次拆分
            while (current.length() > size) {
                int splitAt = findSplitPoint(current.toString(), size - overlap);
                String chunk = current.substring(0, splitAt).trim();
                chunks.add(chunk);
                current = new StringBuilder(current.substring(
                        Math.max(0, splitAt - overlap)));
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /** 在合适的位置拆分 (优先句子边界) */
    private int findSplitPoint(String text, int maxLength) {
        if (text.length() <= maxLength) return text.length();
        // 优先找句号
        int lastDot = text.substring(0, maxLength).lastIndexOf('。');
        if (lastDot > maxLength / 2) return lastDot + 1;
        int lastPeriod = text.substring(0, maxLength).lastIndexOf('.');
        if (lastPeriod > maxLength / 2) return lastPeriod + 1;
        // 退而寻找空格
        int lastSpace = text.substring(0, maxLength).lastIndexOf(' ');
        if (lastSpace > 0) return lastSpace + 1;
        return maxLength;
    }
}
