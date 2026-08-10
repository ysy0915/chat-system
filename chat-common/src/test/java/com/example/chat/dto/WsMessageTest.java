package com.example.chat.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WsMessageTest {

    @Test
    @DisplayName("静态工厂 done")
    void testDone() {
        WsMessage msg = WsMessage.done();
        assertEquals(WsMessage.TYPE_DONE, msg.getType());
    }

    @Test
    @DisplayName("静态工厂 streamStart")
    void testStreamStart() {
        WsMessage msg = WsMessage.streamStart("gpt-4");
        assertEquals(WsMessage.TYPE_STREAM_START, msg.getType());
        Map<String, Object> map = msg.toMap();
        assertEquals("gpt-4", map.get("model"));
    }

    @Test
    @DisplayName("静态工厂 streamToken")
    void testStreamToken() {
        WsMessage msg = WsMessage.streamToken("hello");
        assertEquals(WsMessage.TYPE_STREAM_TOKEN, msg.getType());
        Map<String, Object> map = msg.toMap();
        assertEquals("hello", map.get("token"));
    }

    @Test
    @DisplayName("静态工厂 streamEnd")
    void testStreamEnd() {
        WsMessage msg = WsMessage.streamEnd();
        assertEquals(WsMessage.TYPE_STREAM_END, msg.getType());
    }

    @Test
    @DisplayName("静态工厂 error")
    void testError() {
        WsMessage msg = WsMessage.error("something wrong");
        assertEquals(WsMessage.TYPE_ERROR, msg.getType());
        assertEquals("something wrong", msg.toMap().get("message"));
    }

    @Test
    @DisplayName("静态工厂 stopped")
    void testStopped() {
        WsMessage msg = WsMessage.stopped("partial answer");
        assertEquals("partial answer", msg.toMap().get("answer"));
    }

    @Test
    @DisplayName("静态工厂 typing")
    void testTyping() {
        WsMessage msg = WsMessage.typing(true);
        assertEquals(true, msg.toMap().get("typing"));
    }

    @Test
    @DisplayName("链式调用 withReqId + with")
    void testChaining() {
        WsMessage msg = WsMessage.of("custom")
                .withReqId("req-123")
                .with("key", "value");
        assertEquals("custom", msg.getType());
        assertEquals("req-123", msg.getReqId());
        Map<String, Object> map = msg.toMap();
        assertEquals("custom", map.get("type"));
        assertEquals("req-123", map.get("req_id"));
        assertEquals("value", map.get("key"));
    }

    @Test
    @DisplayName("toMap 含 time")
    void testToMapHasTime() {
        WsMessage msg = WsMessage.done().withReqId("r1");
        Map<String, Object> map = msg.toMap();
        assertNotNull(map.get("time"));
        assertEquals("done", map.get("type"));
    }

    @Test
    @DisplayName("getter/setter")
    void testGetterSetter() {
        WsMessage msg = new WsMessage();
        msg.setType("test");
        msg.setReqId("r99");
        msg.setTime(12345L);
        assertEquals("test", msg.getType());
        assertEquals("r99", msg.getReqId());
        assertEquals(12345L, msg.getTime());
    }

    @Test
    @DisplayName("toString")
    void testToString() {
        WsMessage msg = WsMessage.done().withReqId("abc");
        assertTrue(msg.toString().contains("done"));
    }

    // ==================== 思考链消息 ====================

    @Test
    @DisplayName("静态工厂 thinkingStart")
    void testThinkingStart() {
        WsMessage msg = WsMessage.thinkingStart();
        assertEquals(WsMessage.TYPE_THINKING_START, msg.getType());
        Map<String, Object> map = msg.toMap();
        assertEquals("thinking_start", map.get("type"));
    }

    @Test
    @DisplayName("静态工厂 thinkingToken")
    void testThinkingToken() {
        WsMessage msg = WsMessage.thinkingToken("分析第一步：确认问题域");
        assertEquals(WsMessage.TYPE_THINKING_TOKEN, msg.getType());
        Map<String, Object> map = msg.toMap();
        assertEquals("分析第一步：确认问题域", map.get("token"));
        assertEquals("thinking_token", map.get("type"));
    }

    @Test
    @DisplayName("thinkingToken 空 token → 仍然正常发送")
    void testThinkingTokenEmpty() {
        WsMessage msg = WsMessage.thinkingToken("");
        assertEquals(WsMessage.TYPE_THINKING_TOKEN, msg.getType());
        assertEquals("", msg.toMap().get("token"));
    }

    @Test
    @DisplayName("thinkingToken 与 streamToken 字段一致")
    void testThinkingTokenStructureMatchesStreamToken() {
        WsMessage streamMsg = WsMessage.streamToken("hello");
        WsMessage thinkMsg = WsMessage.thinkingToken("world");

        assertEquals("stream_token", streamMsg.toMap().get("type"));
        assertEquals("thinking_token", thinkMsg.toMap().get("type"));

        // 两者都有 "token" 字段
        assertNotNull(streamMsg.toMap().get("token"));
        assertNotNull(thinkMsg.toMap().get("token"));
    }
}
