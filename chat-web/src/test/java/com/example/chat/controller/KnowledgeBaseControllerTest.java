package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseController 真实行为断言：
 * Authorization 透传转发到 chat-llm、空文件列表 400、多文件逐个上传。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTest {

    @Mock
    private CoreClient coreClient;
    @Mock
    private HttpServletRequest request;

    private KnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeBaseController(coreClient);
    }

    @Test
    void listKnowledgeBases_forwardsAuthHeader() {
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");
        when(coreClient.listKnowledgeBases("Bearer abc")).thenReturn(Map.of("kbs", List.of()));

        ResponseEntity<?> resp = controller.listKnowledgeBases(request);

        assertEquals(200, resp.getStatusCode().value());
        verify(coreClient).listKnowledgeBases("Bearer abc");
    }

    @Test
    void createKnowledgeBase_forwardsBodyAndAuth() {
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");
        Map<String, Object> body = Map.of("name", "资料库");
        when(coreClient.createKnowledgeBase(body, "Bearer abc")).thenReturn(Map.of("id", 1));

        ResponseEntity<?> resp = controller.createKnowledgeBase(body, request);

        assertEquals(200, resp.getStatusCode().value());
        verify(coreClient).createKnowledgeBase(body, "Bearer abc");
    }

    @Test
    void deleteKnowledgeBase_forwardsIdAndAuth() {
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");
        when(coreClient.deleteKnowledgeBase(3L, "Bearer abc")).thenReturn(Map.of("deleted", true));

        controller.deleteKnowledgeBase(3L, request);

        verify(coreClient).deleteKnowledgeBase(3L, "Bearer abc");
    }

    @Test
    void listDocuments_forwardsIdAndAuth() {
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");
        when(coreClient.listDocuments(3L, "Bearer abc")).thenReturn(List.of());

        controller.listDocuments(3L, request);

        verify(coreClient).listDocuments(3L, "Bearer abc");
    }

    @Test
    void uploadDocuments_emptyFiles_400AndNoForward() {
        ResponseEntity<?> resp = controller.uploadDocuments(1L, List.of(), request);

        assertEquals(400, resp.getStatusCode().value());
        verify(coreClient, never()).uploadDocument(any(), any(), any());
    }

    @Test
    void uploadDocuments_uploadsEachFile() {
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");
        var file1 = new MockMultipartFile("files", "a.pdf", "application/pdf", new byte[]{1});
        var file2 = new MockMultipartFile("files", "b.pdf", "application/pdf", new byte[]{2});

        ResponseEntity<?> resp = controller.uploadDocuments(1L, List.of(file1, file2), request);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertEquals(2, body.get("count"));
        verify(coreClient).uploadDocument(eq(1L), eq(file1), eq("Bearer abc"));
        verify(coreClient).uploadDocument(eq(1L), eq(file2), eq("Bearer abc"));
    }

    @Test
    void deleteDocument_forwardsDocumentIdAndAuth() {
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");
        when(coreClient.deleteDocument(7L, "Bearer abc")).thenReturn(Map.of("deleted", true));

        controller.deleteDocument(1L, 7L, request);

        verify(coreClient).deleteDocument(7L, "Bearer abc");
    }
}
