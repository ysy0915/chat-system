package com.example.chat.service;

import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OnlineCountScheduler 单元测试
 * 覆盖：空页面统计、多页面统计、单页面记录写入、DB 异常容错
 */
@ExtendWith(MockitoExtension.class)
class OnlineCountSchedulerTest {

    @Mock
    private WebSocketSessionTracker sessionTracker;

    @Mock
    private OnlineCountRepository onlineCountRepository;

    @Mock
    private OnlineCountRedisService onlineCountRedisService;

    private OnlineCountScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OnlineCountScheduler(sessionTracker, onlineCountRepository, onlineCountRedisService);
    }

    @Test
    @DisplayName("无在线用户时不抛异常")
    void recordOnlineCounts_empty_noException() {
        when(sessionTracker.getAllCounts()).thenReturn(Collections.emptyMap());

        assertDoesNotThrow(() -> scheduler.recordOnlineCounts());

        verify(onlineCountRedisService).recordSnapshot(eq(Collections.emptyMap()), any());
        verify(onlineCountRepository, never()).insert(any());
    }

    @Test
    @DisplayName("多个页面时每个页面各写入一条记录")
    void recordOnlineCounts_multiplePages_eachWritten() {
        Map<String, Integer> counts = Map.of(
                "chat", 15, "treehole", 8, "debate", 3
        );
        when(sessionTracker.getAllCounts()).thenReturn(counts);

        scheduler.recordOnlineCounts();

        verify(onlineCountRedisService).recordSnapshot(eq(counts), any());
        verify(onlineCountRepository, times(3)).insert(any(OnlineCountRecord.class));
    }

    @Test
    @DisplayName("写入记录包含正确的 page 和 count")
    void recordOnlineCounts_recordHasCorrectFields() {
        Map<String, Integer> counts = Map.of("chat", 42);
        when(sessionTracker.getAllCounts()).thenReturn(counts);

        ArgumentCaptor<OnlineCountRecord> captor = ArgumentCaptor.forClass(OnlineCountRecord.class);
        scheduler.recordOnlineCounts();

        verify(onlineCountRepository).insert(captor.capture());
        OnlineCountRecord record = captor.getValue();
        assertEquals("chat", record.page);
        assertEquals(42, record.count);
        assertNotNull(record.recordedAt);
    }

    @Test
    @DisplayName("DB 写入异常时不传播（容错）")
    void recordOnlineCounts_dbException_notPropagated() {
        Map<String, Integer> counts = Map.of("chat", 10, "treehole", 5);
        when(sessionTracker.getAllCounts()).thenReturn(counts);
        doThrow(new DataAccessException("DB 不可用") {}).when(onlineCountRepository).insert(any());

        assertDoesNotThrow(() -> scheduler.recordOnlineCounts());

        // Redis 快照仍然记录
        verify(onlineCountRedisService).recordSnapshot(eq(counts), any());
    }
}
