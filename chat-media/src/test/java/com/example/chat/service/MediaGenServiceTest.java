package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaGenService 单元测试
 * Java 26 兼容：不 mock MyBatis @Mapper 接口
 */
class MediaGenServiceTest {

    // ────────── modelId 常量映射 ──────────

    @Test
    @DisplayName("IMAGE_MODEL_ID 常量值为4")
    void imageModelId_is4() {
        assertEquals(4L, MediaGenService.IMAGE_MODEL_ID);
    }

    @Test
    @DisplayName("VIDEO_MODEL_ID 常量值为5")
    void videoModelId_is5() {
        assertEquals(5L, MediaGenService.VIDEO_MODEL_ID);
    }

    @Test
    @DisplayName("MODEL3D_MODEL_ID 常量值为7")
    void model3DModelId_is7() {
        assertEquals(7L, MediaGenService.MODEL3D_MODEL_ID);
    }

    // ────────── 超时常量 ──────────

    @Test
    @DisplayName("IMAGE_TIMEOUT 为120秒")
    void imageTimeout_is120s() {
        assertEquals(120, MediaGenService.IMAGE_TIMEOUT.toSeconds());
    }

    @Test
    @DisplayName("VIDEO_POLL_INTERVAL_MS 为10000ms")
    void videoPollInterval_is10s() {
        assertEquals(10000, MediaGenService.VIDEO_POLL_INTERVAL_MS);
    }

    @Test
    @DisplayName("VIDEO_MAX_POLL_COUNT 为360")
    void videoMaxPollCount_is360() {
        assertEquals(360, MediaGenService.VIDEO_MAX_POLL_COUNT);
    }

    @Test
    @DisplayName("MODEL3D_MAX_POLL_COUNT 为60")
    void model3DMaxPollCount_is60() {
        assertEquals(60, MediaGenService.MODEL3D_MAX_POLL_COUNT);
    }
}
