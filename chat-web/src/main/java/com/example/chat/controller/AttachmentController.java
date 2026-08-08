package com.example.chat.controller;

import com.example.chat.entity.Attachment;
import com.example.chat.repository.AttachmentRepository;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
    private final AttachmentRepository attachmentRepository;
    private final Path storageRoot;

    public AttachmentController(AttachmentRepository attachmentRepository) throws IOException {
        this.attachmentRepository = attachmentRepository;
        this.storageRoot = Paths.get("uploads");
        Files.createDirectories(this.storageRoot);
    }

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, @RequestParam(value = "uploadedBy", required = false) Long uploadedBy, @RequestParam(value = "messageId", required = false) Long messageId) throws IOException {
        String original = StringUtils.cleanPath(file.getOriginalFilename());
        String ext = "";
        int idx = original.lastIndexOf('.');
        if (idx >= 0) ext = original.substring(idx);
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
