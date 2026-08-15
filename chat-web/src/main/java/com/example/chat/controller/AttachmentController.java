package com.example.chat.controller;

import com.example.chat.entity.Attachment;
import com.example.chat.repository.AttachmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Tag(name = "附件管理", description = "文件上传与存储")
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
    private final AttachmentRepository attachmentRepository;
    private final Path storageRoot;

    /** 允许上传的文件扩展名白名单（小写） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
            ".pdf", ".txt", ".md", ".csv", ".json",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".mp3", ".mp4", ".mov"
    );

    /** 单文件大小上限（10MB，与全局 multipart 限制一致，代码层兜底） */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    public AttachmentController(AttachmentRepository attachmentRepository) throws IOException {
        this.attachmentRepository = attachmentRepository;
        this.storageRoot = Paths.get("uploads");
        Files.createDirectories(this.storageRoot);
    }

    @Operation(summary = "上传文件", description = "上传附件并保存到本地存储，返回附件 ID 和访问 URL")
    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, @RequestParam(value = "uploadedBy", required = false) Long uploadedBy, @RequestParam(value = "messageId", required = false) Long messageId) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());

        // 1) 扩展名白名单校验（防恶意脚本/可执行文件上传）
        String ext = "";
        int idx = original.lastIndexOf('.');
        if (idx >= 0) ext = original.substring(idx).toLowerCase(Locale.ROOT);
        if (ext.isEmpty() || !ALLOWED_EXTENSIONS.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "不支持的文件类型: " + ext));
        }

        // 2) 大小二次校验（代码层兜底，防绕过 Nginx/容器 multipart 限制）
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件过大，最大支持 10MB"));
        }

        // 3) 用 UUID 文件名落盘，杜绝路径穿越（丢弃原始文件名作为存储名）
        String name = UUID.randomUUID().toString() + ext;
        Path target = storageRoot.resolve(name);
        Files.copy(file.getInputStream(), target);

        Attachment a = new Attachment();
        a.messageId = messageId;
        a.uploadedBy = uploadedBy == null ? 0L : uploadedBy;
        a.storageUrl = target.toAbsolutePath().toString();
        a.mimeType = file.getContentType();
        a.filename = original;
        a.size = file.getSize();
        attachmentRepository.insert(a);

        return ResponseEntity.ok(Map.of("id", a.id, "url", a.storageUrl));
    }
}
