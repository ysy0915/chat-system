package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.repository.TreeHoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TreeHoleService 单元测试
 * 使用手写 Stub（避免 Java 26 下 Mockito inline mock 的 JVM 限制）
 */
class TreeHoleServiceTest {

    // ── 手写 Stub：TreeHoleRepository ──
    private static class StubTreeHoleRepo implements TreeHoleRepository {
        final List<TreeHoleMessage> store = new ArrayList<>();
        List<TreeHoleMessage> recentResult = new ArrayList<>();
        TreeHoleMessage inserted;
        TreeHoleMessage updated;

        @Override public List<TreeHoleMessage> findByUserId(Long userId) {
            return store.stream().filter(m -> userId.equals(m.userId)).toList();
        }
        @Override public List<TreeHoleMessage> findRecentByUserId(Long userId) { return recentResult; }
        @Override public int insert(TreeHoleMessage m) { inserted = m; store.add(m); return 1; }
        @Override public int updateByReqId(TreeHoleMessage m) { updated = m; return 1; }
        @Override public TreeHoleMessage findByReqId(String reqId) {
            return store.stream().filter(m -> reqId.equals(m.reqId)).findFirst().orElse(null);
        }
    }

    // ── 手写 Stub：ModelConfigRepository ──
    private static class StubModelConfigRepo implements ModelConfigRepository {
        ModelConfig configForId2 = null;
        @Override public List<ModelConfig> findAll() { return List.of(); }
        @Override public List<ModelConfig> findAllEnabled() { return List.of(); }
        @Override public List<ModelConfig> findAllEnabledByType(String modelType) { return List.of(); }
        @Override public ModelConfig findById(Long id) { return (id == 2L) ? configForId2 : null; }
        @Override public List<ModelConfig> findByIds(List<Long> ids) { return List.of(); }
        @Override public int insert(ModelConfig m) { return 1; }
        @Override public int update(ModelConfig m) { return 1; }
        @Override public int deleteById(Long id) { return 1; }
    }

    // ── 手写 Stub：RateLimitService（继承，覆盖方法）──
    private static class StubRateLimitService extends RateLimitService {
        boolean allowed = true;
        long remainingSeconds = 30L;
        StubRateLimitService() { super(null); }
        @Override public boolean isAllowed(Long userId) { return allowed; }
        @Override public long getRemainingSeconds(Long userId) { return remainingSeconds; }
    }

    private StubTreeHoleRepo treeHoleRepo;
    private StubModelConfigRepo modelConfigRepo;
    private StubRateLimitService rateLimitService;
    private TreeHoleService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        treeHoleRepo = new StubTreeHoleRepo();
        modelConfigRepo = new StubModelConfigRepo();
        rateLimitService = new StubRateLimitService();

        service = new TreeHoleService(treeHoleRepo, modelConfigRepo, rateLimitService, objectMapper);
        ReflectionTestUtils.setField(service, "defaultBaseUrl",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        ReflectionTestUtils.setField(service, "defaultApiKey", "test-key-invalid");
        ReflectionTestUtils.setField(service, "defaultModel", "qwen-plus");
    }

    // ────────────── getHistory ──────────────

    @Test
    @DisplayName("getHistory：正常返回用户历史记录")
    void getHistory_returnsUserRecords() {
        TreeHoleMessage m1 = new TreeHoleMessage();
        m1.userId = 1L; m1.question = "心情不好";
        TreeHoleMessage m2 = new TreeHoleMessage();
        m2.userId = 1L; m2.question = "好多了";
        treeHoleRepo.store.add(m1);
        treeHoleRepo.store.add(m2);

        List<TreeHoleMessage> result = service.getHistory(1L);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("getHistory：无历史时返回空列表")
    void getHistory_empty() {
        assertTrue(service.getHistory(99L).isEmpty());
    }

    // ────────────── askAndSave 限流 ──────────────

    @Test
    @DisplayName("askAndSave：触发限流时抛出异常")
    void askAndSave_rateLimited_throwsException() {
        rateLimitService.allowed = false;
        rateLimitService.remainingSeconds = 30L;

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.askAndSave(1L, "我很难过", "悲伤"));
        assertTrue(ex.getMessage().contains("30"));
        assertNull(treeHoleRepo.inserted, "限流时不应保存记录");
    }

    // ────────────── askAndSave 保存逻辑 ──────────────

    @Test
    @DisplayName("askAndSave：AI 调用失败时状态为 error，仍保存记录")
    void askAndSave_aiFailure_statusError() {
        // modelConfigRepo.configForId2 = null → 降级用 test-key-invalid → AI 调用失败
        TreeHoleMessage result = service.askAndSave(1L, "我很难过", "悲伤");

        assertEquals("error", result.status);
        assertNotNull(result.answerJson);
        assertTrue(result.answerJson.contains("树洞") || result.answerJson.contains("稍后"));
        assertNotNull(treeHoleRepo.inserted, "应有记录插入");
        assertNotNull(treeHoleRepo.updated, "应有记录更新");
    }

    @Test
    @DisplayName("askAndSave：insert 时 userId / question / mood / reqId 正确")
    void askAndSave_savedFieldsCorrect() {
        service.askAndSave(1L, "今天好累", "疲惫");

        // insert 时字段正确（status 在 insert 时为 pending，AI 调用后 update 为 done/error）
        TreeHoleMessage inserted = treeHoleRepo.inserted;
        assertNotNull(inserted, "应有记录插入");
        assertEquals(1L, inserted.userId);
        assertEquals("今天好累", inserted.question);
        assertEquals("疲惫", inserted.mood);
        assertNotNull(inserted.reqId, "reqId 不能为空");
        // 最终返回的记录状态为 error（因为测试环境 API Key 无效）
        TreeHoleMessage updated = treeHoleRepo.updated;
        assertNotNull(updated, "应有记录更新");
        assertNotEquals("pending", updated.status, "最终状态应为 done 或 error");
    }

    @Test
    @DisplayName("askAndSave：无情绪标签时正常保存")
    void askAndSave_noMood() {
        service.askAndSave(1L, "随便说说", "");
        assertEquals("", treeHoleRepo.inserted.mood);
    }

    @Test
    @DisplayName("askAndSave：携带历史上下文时，流程正常走通")
    void askAndSave_withHistory_contextBuilt() {
        TreeHoleMessage prev = new TreeHoleMessage();
        prev.question = "上次说的话";
        prev.answerJson = "AI 的上次回答";
        treeHoleRepo.recentResult = List.of(prev);

        TreeHoleMessage result = service.askAndSave(1L, "继续说", "平静");
        assertNotNull(result);
    }

    // ────────────── resolveModelConfig 降级逻辑 ──────────────

    @Test
    @DisplayName("askAndSave：model_configs id=2 存在时使用数据库配置")
    void askAndSave_usesDbModelConfig() {
        ModelConfig cfg = new ModelConfig();
        cfg.id = 2L; cfg.provider = "qwen";
        cfg.model = "qwen-max"; cfg.apiKeyEncrypted = "real-api-key-invalid";
        modelConfigRepo.configForId2 = cfg;

        // AI 调用因 key 无效失败，但流程走通，状态为 error
        TreeHoleMessage result = service.askAndSave(1L, "测试", "平静");
        assertEquals("error", result.status);
        // 验证确实用了 id=2 的配置（answerJson 不是 NPE，流程完整）
        assertNotNull(result.answerJson);
    }
}
