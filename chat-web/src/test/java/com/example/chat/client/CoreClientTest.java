package com.example.chat.client;

import com.example.chat.exception.ChatServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CoreClient 真实行为断言：
 * init 地址加载与去重、GET 转发与双实例故障转移、POST payload 组装（文件 base64）、stop 广播、全失败抛异常。
 */
@ExtendWith(MockitoExtension.class)
class CoreClientTest {

    @Mock
    private RestTemplate restTemplate;

    private CoreClient client;

    @BeforeEach
    void setUp() {
        client = new CoreClient(restTemplate);
        ReflectionTestUtils.setField(client, "coreBaseUrl", "http://127.0.0.1:9090");
        ReflectionTestUtils.setField(client, "coreBaseUrlsExtra", "");
    }

    /** 单地址：请求转发到唯一 core URL */
    @Test
    void listMessages_singleUrl_forwardsToCore() {
        when(restTemplate.getForEntity("http://127.0.0.1:9090/internal/messages?user_id=1", Object.class))
                .thenReturn(ResponseEntity.ok(Map.of("rows", 1)));

        client.init();
        Object result = client.listMessages(1L);

        assertEquals(Map.of("rows", 1), result);
        verify(restTemplate).getForEntity("http://127.0.0.1:9090/internal/messages?user_id=1", Object.class);
    }

    /** 多地址：extra 去重（重复 9090、空项被剔除）→ 故障转移打到第二个 URL */
    @Test
    void init_multiUrl_deduplicatesAndFailsOver() {
        ReflectionTestUtils.setField(client, "coreBaseUrlsExtra", " http://127.0.0.1:9092 ,http://127.0.0.1:9090, ");
        when(restTemplate.getForEntity(anyString(), eq(Object.class)))
                .thenThrow(new RuntimeException("first down"))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        client.init();
        Object result = client.listMessages(1L);

        assertEquals(Map.of("ok", true), result);
        // 首次打到 9090（首个地址）失败 → 故障转移到 9092 成功
        verify(restTemplate).getForEntity("http://127.0.0.1:9090/internal/messages?user_id=1", Object.class);
        verify(restTemplate).getForEntity("http://127.0.0.1:9092/internal/messages?user_id=1", Object.class);
    }

    /** 所有实例失败 → 抛 ChatServiceException */
    @Test
    void get_allInstancesFail_throwsChatServiceException() {
        ReflectionTestUtils.setField(client, "coreBaseUrlsExtra", "http://127.0.0.1:9092");
        when(restTemplate.getForEntity(anyString(), eq(Object.class)))
                .thenThrow(new RuntimeException("down"))
                .thenThrow(new RuntimeException("down"));

        client.init();
        ChatServiceException ex = assertThrows(ChatServiceException.class, () -> client.listMessages(1L));

        assertTrue(ex.getMessage().contains("调用核心服务失败"));
        verify(restTemplate, times(2)).getForEntity(anyString(), eq(Object.class));
    }

    /** chatProcessWithFile：payload 组装含 base64 文件数据 */
    @Test
    void chatProcessWithFile_buildsPayloadWithBase64FileData() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(null));

        client.init();
        client.chatProcessWithFile("req-1", 9L, "看图说话", "a.png",
                new byte[]{1, 2, 3}, "image/png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq("http://127.0.0.1:9090/internal/chat/process-with-file"), captor.capture(), eq(Object.class));
        Map<String, Object> payload = captor.getValue().getBody();
        assertEquals("req-1", payload.get("req_id"));
        assertEquals(9L, payload.get("user_id"));
        assertEquals("看图说话", payload.get("question"));
        assertEquals("a.png", payload.get("file_name"));
        assertEquals("image/png", payload.get("mime_type"));
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), payload.get("file_data"));
    }

    /** chatStop：双 core 下广播到所有实例 */
    @Test
    void chatStop_multiInstance_broadcastsToAll() {
        ReflectionTestUtils.setField(client, "coreBaseUrlsExtra", "http://127.0.0.1:9092");
        when(restTemplate.postForEntity(anyString(), any(), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(null));

        client.init();
        client.chatStop("req-9");

        verify(restTemplate).postForEntity(eq("http://127.0.0.1:9090/internal/chat/stop"), any(), eq(Object.class));
        verify(restTemplate).postForEntity(eq("http://127.0.0.1:9092/internal/chat/stop"), any(), eq(Object.class));
    }

    /** broadcast：一个实例失败另一个成功 → 不抛异常 */
    @Test
    void broadcast_oneInstanceFails_otherSucceeds_noThrow() {
        ReflectionTestUtils.setField(client, "coreBaseUrlsExtra", "http://127.0.0.1:9092");
        when(restTemplate.postForEntity(eq("http://127.0.0.1:9090/internal/chat/stop"), any(), eq(Object.class)))
                .thenThrow(new RuntimeException("down"));
        when(restTemplate.postForEntity(eq("http://127.0.0.1:9092/internal/chat/stop"), any(), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(null));

        client.init();
        assertDoesNotThrow(() -> client.chatStop("req-9"));
    }
}
