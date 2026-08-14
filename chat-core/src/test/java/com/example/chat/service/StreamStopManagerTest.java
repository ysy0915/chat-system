package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamStopManager 单元测试
 */
@DisplayName("StreamStopManager 流式停止管理器")
class StreamStopManagerTest {

    private StreamStopManager manager;

    @BeforeEach
    void setUp() {
        manager = new StreamStopManager();
    }

    @Test
    @DisplayName("requestStop 后 isStopped 返回 true")
    void requestStop_thenIsStoppedTrue() {
        String reqId = "req-001";
        manager.requestStop(reqId);
        assertTrue(manager.isStopped(reqId));
    }

    @Test
    @DisplayName("未 requestStop 的 reqId isStopped 返回 false")
    void notStopped_returnsFalse() {
        assertFalse(manager.isStopped("non-existent"));
    }

    @Test
    @DisplayName("getOrDefault 对未知 reqId 返回 false 的 AtomicBoolean")
    void getOrDefault_unknownReqId_returnsFalse() {
        AtomicBoolean flag = manager.getOrDefault("unknown");
        assertNotNull(flag);
        assertFalse(flag.get());
    }

    @Test
    @DisplayName("getOrDefault 对已停止 reqId 返回 true 的 AtomicBoolean")
    void getOrDefault_stoppedReqId_returnsTrue() {
        manager.requestStop("req-002");
        AtomicBoolean flag = manager.getOrDefault("req-002");
        assertTrue(flag.get());
    }

    @Test
    @DisplayName("remove 后 isStopped 返回 false")
    void remove_clearsStopFlag() {
        String reqId = "req-003";
        manager.requestStop(reqId);
        assertTrue(manager.isStopped(reqId));
        manager.remove(reqId);
        assertFalse(manager.isStopped(reqId));
    }

    @Test
    @DisplayName("clear 清除所有停止标记")
    void clear_removesAll() {
        manager.requestStop("a");
        manager.requestStop("b");
        manager.clear();
        assertFalse(manager.isStopped("a"));
        assertFalse(manager.isStopped("b"));
    }

    @Test
    @DisplayName("多次 requestStop 同一 reqId 幂等")
    void requestStop_idempotent() {
        String reqId = "req-004";
        manager.requestStop(reqId);
        manager.requestStop(reqId);
        assertTrue(manager.isStopped(reqId));
        manager.remove(reqId);
        assertFalse(manager.isStopped(reqId));
    }
}
