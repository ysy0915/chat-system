package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentParser 单元测试
 */
@DisplayName("DocumentParser 文档解析器")
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    @DisplayName("TXT 文件解析为 UTF-8 文本")
    void parse_txt_returnsContent() {
        String content = "这是一段测试文本内容";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse("test.txt", bytes);
        assertEquals(content, result);
    }

    @Test
    @DisplayName("Markdown 文件解析为纯文本")
    void parse_md_returnsContent() {
        String content = "# 标题\n\n正文内容";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse("readme.md", bytes);
        assertEquals(content, result);
    }

    @Test
    @DisplayName("JSON 文件解析为纯文本")
    void parse_json_returnsContent() {
        String content = "{\"key\":\"value\"}";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse("config.json", bytes);
        assertEquals(content, result);
    }

    @Test
    @DisplayName("null 文件名返回空字符串")
    void parse_nullFileName_returnsEmpty() {
        assertEquals("", parser.parse(null, new byte[]{1, 2}));
    }

    @Test
    @DisplayName(".doc 格式抛出异常（不支持）")
    void parse_doc_throwsException() {
        assertThrows(RuntimeException.class, () -> parser.parse("old.doc", new byte[]{1, 2}));
    }

    @Test
    @DisplayName("无效 PDF 抛出异常")
    void parse_invalidPdf_throwsException() {
        assertThrows(RuntimeException.class, () ->
                parser.parse("test.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("大写后缀也能正确识别")
    void parse_upperCaseExtension_parsed() {
        String content = "大写后缀测试";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse("TEST.TXT", bytes);
        assertEquals(content, result);
    }
}
