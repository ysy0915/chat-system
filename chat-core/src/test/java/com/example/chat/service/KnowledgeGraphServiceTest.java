package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeGraphService 单元测试（编排层）
 * 聚焦守卫条件：启用/禁用、驱动降级、批量导入状态
 * Java 26 兼容：不 mock 外部模块接口
 */
class KnowledgeGraphServiceTest {

    private KnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeGraphService(null, null, null, null);
        ReflectionTestUtils.setField(service, "enabled", false);
    }

    // ────────── extractAndSaveAsync 守卫条件 ──────────

    @Test
    @DisplayName("extractAndSaveAsync：图谱未启用时直接返回")
    void extractAndSaveAsync_whenDisabled() {
        assertDoesNotThrow(() -> service.extractAndSaveAsync(1L, "问题", "答案", "personal"));
    }

    @Test
    @DisplayName("extractAndSaveAsync：question和answer都为空时跳过（未启用状态）")
    void extractAndSaveAsync_whenBlankAll() {
        assertDoesNotThrow(() -> service.extractAndSaveAsync(1L, "", null, "personal"));
    }

    @Test
    @DisplayName("extractAndSaveAsync：messageId为null仍正常处理")
    void extractAndSaveAsync_whenNullMessageId() {
        assertDoesNotThrow(() -> service.extractAndSaveAsync(null, "问题", "答案", "personal"));
    }

    // ────────── 批量导入守卫 ──────────

    @Test
    @DisplayName("startBatchImport：未启用时返回false")
    void startBatchImport_whenDisabled() {
        assertFalse(service.startBatchImport());
    }

    @Test
    @DisplayName("startBatchImport：driver为null时返回false")
    void startBatchImport_whenDriverNull() {
        ReflectionTestUtils.setField(service, "enabled", true);
        assertFalse(service.startBatchImport());
    }

    @Test
    @DisplayName("isImporting：初始状态为false")
    void isImporting_initialState() {
        assertFalse(service.isImporting());
    }
}
