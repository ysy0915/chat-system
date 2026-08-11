package com.example.chat.llm.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析器 — 支持 PDF / DOCX / TXT。
 */
@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    /**
     * 解析文档为纯文本。
     *
     * @param content     文档二进制内容
     * @param contentType MIME 类型
     * @return 解析出的纯文本
     */
    public String parse(byte[] content, String contentType) {
        if (contentType == null) {
            contentType = detectType(content);
        }
        try {
            return switch (contentType.toLowerCase()) {
                case "application/pdf" -> parsePdf(content);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocx(content);
                default -> new String(content, StandardCharsets.UTF_8);
            };
        } catch (Exception e) {
            log.warn("文档解析失败 type={}: {}", contentType, e.getMessage());
            // 降级为 UTF-8 文本
            try {
                return new String(content, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return "[文档解析失败: " + e.getMessage() + "]";
            }
        }
    }

    private String parsePdf(byte[] content) throws IOException {
        try (var doc = Loader.loadPDF(content)) {
            var stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String parseDocx(byte[] content) throws IOException {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            });
            return sb.toString();
        }
    }

    /** 通过文件头魔数检测类型 */
    private String detectType(byte[] content) {
        if (content.length < 4) return "text/plain";
        int b0 = content[0] & 0xFF;
        int b1 = content[1] & 0xFF;
        int b2 = content[2] & 0xFF;
        int b3 = content[3] & 0xFF;
        // PDF: %PDF
        if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) {
            return "application/pdf";
        }
        // DOCX: PK..
        if (b0 == 0x50 && b1 == 0x4B) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "text/plain";
    }
}
