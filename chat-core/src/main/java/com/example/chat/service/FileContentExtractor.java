package com.example.chat.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * 文件内容提取器 — 从 Excel/PPT 文件中提取文本内容。
 */
@Service
public class FileContentExtractor {
    private static final Logger log = LoggerFactory.getLogger(FileContentExtractor.class);

    public String extract(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) return "";
        String lower = fileName != null ? fileName.toLowerCase() : "";
        try {
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                return extractExcel(bytes);
            } else if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) {
                return extractPpt(bytes);
            }
        } catch (Exception e) {
            log.warn("[FileExtract] Failed to extract {}: {}", fileName, e.getMessage());
        }
        return "";
    }

    private String extractExcel(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                var sheet = wb.getSheetAt(i);
                sb.append("\n--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");
                for (var row : sheet) {
                    for (var cell : row) {
                        switch (cell.getCellType()) {
                            case STRING -> sb.append(cell.getStringCellValue()).append("\t");
                            case NUMERIC -> sb.append(cell.getNumericCellValue()).append("\t");
                            case BOOLEAN -> sb.append(cell.getBooleanCellValue()).append("\t");
                            default -> sb.append("\t");
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.length() > 10000 ? sb.substring(0, 10000) + "\n...(truncated)" : sb.toString();
    }

    private String extractPpt(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < ppt.getSlides().size(); i++) {
                var slide = ppt.getSlides().get(i);
                sb.append("\n--- Slide ").append(i + 1).append(" ---\n");
                for (var shape : slide.getShapes()) {
                    String text = shape.getShapeName() + " ";
                    sb.append(text);
                }
            }
        }
        return sb.length() > 10000 ? sb.substring(0, 10000) + "\n...(truncated)" : sb.toString();
    }
}
