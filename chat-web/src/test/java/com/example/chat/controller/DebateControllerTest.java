package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.service.ContentSafetyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DebateController 真实行为断言：
 * 敏感词 400、rounds 钳制 1-10、mode 透传、coreClient 调用序列（insertDebateRecord → insertMessage → debateStart）。
 */
@ExtendWith(MockitoExtension.class)
class DebateControllerTest {

    @Mock
    private CoreClient coreClient;
    @Mock
    private ContentSafetyService contentSafetyService;

    private DebateController controller;

    @BeforeEach
    void setUp() {
        controller = new DebateController(coreClient, contentSafetyService);
    }

    @Test
    void startDebate_sensitiveContent_400AndNoForward() {
        when(contentSafetyService.detectSensitive("非法内容")).thenReturn("politics");
        when(contentSafetyService.getLabelHint("politics")).thenReturn("问题涉及敏感政治内容，请修改后重试");

        ResponseEntity<?> resp = controller.startDebate(Map.of("question", "非法内容"));

        assertEquals(400, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue(body.get("error").toString().contains("敏感政治内容"));
        verify(coreClient, never()).debateStart(any());
        verify(coreClient, never()).insertDebateRecord(any());
    }

    @Test
    void startDebate_roundsClampedTo10_modePassthrough() {
        when(contentSafetyService.detectSensitive("正常问题")).thenReturn(null);

        ResponseEntity<?> resp = controller.startDebate(Map.of(
                "question", "正常问题", "user_id", 3L, "rounds", 99, "mode", "tree"));

        assertEquals(202, resp.getStatusCode().value());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(coreClient).debateStart(payload.capture());
        assertEquals(10, payload.getValue().get("rounds"));
        assertEquals("tree", payload.getValue().get("mode"));
        assertEquals("正常问题", payload.getValue().get("question"));
        assertEquals(3L, payload.getValue().get("user_id"));
        verify(coreClient).insertDebateRecord(any());
        verify(coreClient).insertMessage(any());
    }

    @Test
    void startDebate_defaultRounds3() {
        when(contentSafetyService.detectSensitive("问题")).thenReturn(null);

        controller.startDebate(Map.of("question", "问题"));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(coreClient).debateStart(payload.capture());
        assertEquals(3, payload.getValue().get("rounds"));
    }

    @Test
    void startDebate_invalidRounds_fallsBackTo3() {
        when(contentSafetyService.detectSensitive("问题")).thenReturn(null);

        controller.startDebate(Map.of("question", "问题", "rounds", "abc"));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(coreClient).debateStart(payload.capture());
        assertEquals(3, payload.getValue().get("rounds"));
    }
}
