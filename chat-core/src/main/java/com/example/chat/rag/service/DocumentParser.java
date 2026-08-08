package com.example.chat.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析器：从上传的文件中提取纯文本
 * 支持 PDF、Word(.docx)、TXT/Markdown
 */
@Service
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    /**
     * 根据文件名后缀解析文档
     * @param fileName 文件名（用于判断类型）
     * @param bytes 文件内容
     * @return 提取的纯文本
     */
    public String parse(String fileName, byte[] bytes) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase();

        try {
            if (lower.endsWith(".pdf")) {
                return parsePdf(bytes);
            } else if (lower.endsWith(".docx")) {
                return parseDocx(bytes);
            } else if (lower.endsWith(".doc")) {
                // 老 .doc 格式 POI 支持有限，提示转换
                throw new RuntimeException("暂不支持 .doc 格式，请转换为 .docx");
            } else {
                // TXT / MD / JSON 等纯文本
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("[DocParser] 解析失败 file={} error={}", fileName, e.getMessage());
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String parseDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            return sb.toString();
        }
    }
}
