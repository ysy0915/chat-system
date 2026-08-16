package com.example.chat.controller;

import com.example.chat.service.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Tag(name = "附件管理", description = "文件上传与存储")
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Operation(summary = "上传文件", description = "上传附件并保存到本地存储，返回附件 ID 和访问 URL")
    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, @RequestParam(value = "uploadedBy", required = false) Long uploadedBy, @RequestParam(value = "messageId", required = false) Long messageId) throws IOException {
        Map<String, Object> result = attachmentService.upload(file, uploadedBy, messageId);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
