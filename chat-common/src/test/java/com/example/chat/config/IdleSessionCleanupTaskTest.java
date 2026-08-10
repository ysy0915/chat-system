package com.example.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * IdleSessionCleanupTask 单元测试
 * 覆盖：正常清理调用、异常容错不传播
 */
@ExtendWith(MockitoExtension.class)
class IdleSessionCleanupTaskTest {

    @Mock
    private WebSocketSessionTracker sessionTracker;

    private IdleSessionCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new IdleSessionCleanupTask(sessionTracker);
    }

    @Test
    @DisplayName("cleanup 调用 sessionTracker.cleanupIdleSessions()")
    void cleanup_delegatesToSessionTracker() {
        task.cleanup();

        verify(sessionTracker).cleanupIdleSessions();
    }

    @Test
    @DisplayName("cleanup 内部异常不传播")
    void cleanup_exception_notPropagated() {
        doThrow(new RuntimeException("Redis 不可用")).when(sessionTracker).cleanupIdleSessions();

        assertDoesNotThrow(() -> task.cleanup());

        verify(sessionTracker).cleanupIdleSessions();
    }

    @Test
    @DisplayName("多次调用 cleanup 都正常委托")
    void cleanup_multipleCalls_alwaysDelegates() {
        task.cleanup();
        task.cleanup();
        task.cleanup();

        verify(sessionTracker, times(3)).cleanupIdleSessions();
    }
}
