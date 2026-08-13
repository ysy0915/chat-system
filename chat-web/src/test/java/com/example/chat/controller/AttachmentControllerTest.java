package com.example.chat.controller;

import com.example.chat.entity.Attachment;
import com.example.chat.repository.AttachmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AttachmentController 真实行为断言：
 * 上传写盘 + 落库（字段透传）+ 返回 id/url；无扩展名文件名处理。
 */
@ExtendWith(MockitoExtension.class)
class AttachmentControllerTest {

    @Mock
    private AttachmentRepository attachmentRepository;

    private AttachmentController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller = new AttachmentController(attachmentRepository);
    }

    @AfterEach
    void cleanupUploads() throws IOException {
        Path uploads = Paths.get("uploads");
        if (Files.exists(uploads)) {
            try (var stream = Files.list(uploads)) {
                stream.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(uploads);
        }
    }

    @Test
    void upload_persistsFileAndRecord_returnsUrl() throws Exception {
        var file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
        // 模拟 MyBatis @Options 回填主键
        when(attachmentRepository.insert(any())).thenAnswer(inv -> {
            inv.<Attachment>getArgument(0).id = 100L;
            return 1;
        });

        ResponseEntity<?> resp = controller.upload(file, 7L, 99L);

        assertEquals(200, resp.getStatusCode().value());
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepository).insert(captor.capture());
        Attachment saved = captor.getValue();
        assertEquals("photo.png", saved.filename);
        assertEquals("image/png", saved.mimeType);
        assertEquals(3L, saved.size);
        assertEquals(7L, saved.uploadedBy);
        assertEquals(99L, saved.messageId);
        assertTrue(saved.storageUrl.contains("uploads"));

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertTrue(body.get("url").toString().contains("uploads"));
        assertEquals(100L, body.get("id"));
    }

    @Test
    void upload_nullUploader_defaultsToZero() throws Exception {
        var file = new MockMultipartFile("file", "note", "text/plain", new byte[]{});
        when(attachmentRepository.insert(any())).thenAnswer(inv -> {
            inv.<Attachment>getArgument(0).id = 101L;
            return 1;
        });

        controller.upload(file, null, null);

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepository).insert(captor.capture());
        assertEquals(0L, captor.getValue().uploadedBy);
        assertNull(captor.getValue().messageId);
        // 无扩展名文件：原始文件名保留在 filename 字段，存储名由 UUID 拼接生成
        assertEquals("note", captor.getValue().filename);
        assertTrue(captor.getValue().storageUrl.contains("uploads"));
    }
}
