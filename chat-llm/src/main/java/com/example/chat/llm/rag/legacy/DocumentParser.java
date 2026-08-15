package com.example.chat.llm.rag.legacy;

import com.example.chat.exception.ChatServiceException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档解析器：从上传的文件中提取纯文本
 * 支持 PDF、Word(.docx)、TXT/Markdown
 */
@Service("legacyDocumentParser")
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    /** 分页解析结果：pageNo 为物理页码（从 1 开始），text 为该页纯文本 */
    public record PageText(int pageNo, String text) {}

    /**
     * 分页解析文档：PDF 按物理页逐页提取（用于引文溯源「第 X 页」）；
     * docx/txt 等无分页概念视为单页（pageNo=1）。
     *
     * @param fileName 文件名（用于判断类型）
     * @param bytes 文件内容
     * @return 分页文本列表（空文档返回空列表）
     */
    public List<PageText> parsePages(String fileName, byte[] bytes) {
        if (fileName == null || bytes == null) {
            return List.of();
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".pdf")) {
                return parsePdfPages(bytes);
            }
            String text = parse(fileName, bytes);
            if (text == null || text.isBlank()) {
                return List.of();
            }
            return List.of(new PageText(1, text));
        } catch (Exception e) {
            log.error("[DocParser] 分页解析失败 file={} error={}", fileName, e.getMessage());
            throw new ChatServiceException("文档解析", "PARSE_FAILED", "文档解析失败: " + fileName, e);
        }
    }

    /**
     * 根据文件名后缀解析文档
     * @param fileName 文件名（用于判断类型）
     * @param bytes 文件内容
     * @return 提取的纯文本
     */
    public String parse(String fileName, byte[] bytes) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);

        try {
            if (lower.endsWith(".pdf")) {
                return parsePdf(bytes);
            } else if (lower.endsWith(".docx")) {
                return parseDocx(bytes);
            } else if (lower.endsWith(".doc")) {
                // 老 .doc 格式 POI 支持有限，提示转换
                throw new ChatServiceException("文档解析", "UNSUPPORTED_FORMAT", "暂不支持 .doc 格式，请转换为 .docx");
            } else {
                // TXT / MD / JSON 等纯文本
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("[DocParser] 解析失败 file={} error={}", fileName, e.getMessage());
            throw new ChatServiceException("文档解析", "PARSE_FAILED", "文档解析失败: " + fileName, e);
        }
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    /** PDF 逐页提取文本，空白页跳过（页码仍保留物理页号，便于引文溯源） */
    private List<PageText> parsePdfPages(byte[] bytes) throws Exception {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int total = doc.getNumberOfPages();
            for (int i = 1; i <= total; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                if (text != null && !text.isBlank()) {
                    pages.add(new PageText(i, text));
                }
            }
        }
        return pages;
    }

    private String parseDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
            return sb.toString();
        }
    }
}
