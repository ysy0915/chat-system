package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.MediaGenRecordRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaGenService 真实行为断言：
 * 类型→模型 ID 映射、模型未配置/API Key 缺失的失败路径。
 * 注：成功路径依赖外部 HTTP 生成，单测仅覆盖确定性失败分支与映射。
 */
@ExtendWith(MockitoExtension.class)
class MediaGenServiceTest {

    @Mock
    private MediaGenRecordRepository recordRepo;

    @Mock
    private ModelConfigRepository modelConfigRepo;

    @Mock
    private BaseUrlResolver baseUrlResolver;

    @Mock
    private OssService ossService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MediaGenService service;

    @BeforeEach
    void setUp() {
        service = new MediaGenService(recordRepo, modelConfigRepo, objectMapper, baseUrlResolver, ossService);
    }

    // ────────── 常量映射 ──────────

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

    // ────────── 类型 → 模型映射 ──────────

    @Test
    @DisplayName("image 类型查询模型 4")
    void generate_imageType_queriesImageModel() {
        when(modelConfigRepo.findById(4L)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.generate("猫", "image", 1L));

        assertTrue(ex.getMessage().contains("模型未配置"), ex.getMessage());
        verify(modelConfigRepo).findById(4L);
    }

    @Test
    @DisplayName("video 类型查询模型 5")
    void generate_videoType_queriesVideoModel() {
        when(modelConfigRepo.findById(5L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.generate("猫", "video", null));

        verify(modelConfigRepo).findById(5L);
    }

    @Test
    @DisplayName("3d 类型查询模型 7")
    void generate_3dType_queries3dModel() {
        when(modelConfigRepo.findById(7L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.generate("猫", "3d", null));

        verify(modelConfigRepo).findById(7L);
    }

    @Test
    @DisplayName("未知类型回退到图片模型")
    void generate_unknownType_fallsBackToImageModel() {
        when(modelConfigRepo.findById(4L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.generate("猫", "video_edit", null));

        verify(modelConfigRepo).findById(4L);
    }

    // ────────── 失败路径 ──────────

    @Test
    @DisplayName("模型 API Key 未配置时抛出且不落库")
    void generate_missingApiKey_throwsBeforePersist() {
        ModelConfig config = new ModelConfig();
        config.id = 4L;
        config.model = "qwen-image";
        when(modelConfigRepo.findById(4L)).thenReturn(config);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.generate("猫", "image", 1L));

        assertTrue(ex.getMessage().contains("API Key 未配置"), ex.getMessage());
        verify(recordRepo, never()).insert(any());
    }
}
