package com.example.chat.llm.rag.legacy;

import com.example.chat.dto.LangChainResponse;
import com.example.chat.entity.UserProfile;
import com.example.chat.llm.service.LLMInvokeService;
import com.example.chat.repository.UserProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationMemoryService 单元测试：
 * 短期/长期记忆保存、记忆上下文拼装（画像/最近对话/相关历史）、
 * 用户画像提炼-合并-落库、清除短期记忆及各依赖缺失时的降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationMemoryService 对话记忆")
class ConversationMemoryServiceTest {

    @Mock
    private MemoryKVStore memoryStore;

    @Mock
    private LegacyEmbeddingService embeddingService;

    @Mock
    private VectorStoreLegacy vectorStoreService;

    @Mock
    private LLMInvokeService llmInvokeService;

    @Mock
    private UserProfileRepository userProfileRepository;

    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationMemoryService(new ObjectMapper());
        service.setMemoryStore(memoryStore);
        service.setEmbeddingService(embeddingService);
        service.setVectorStoreService(vectorStoreService);
        service.setLlmInvokeService(llmInvokeService);
        service.setUserProfileRepository(userProfileRepository);
        ReflectionTestUtils.setField(service, "shortTermRounds", 5);
        ReflectionTestUtils.setField(service, "longTermTopK", 3);
        ReflectionTestUtils.setField(service, "longTermThreshold", 0.5f);
        ReflectionTestUtils.setField(service, "redisTtlHours", 24);
        ReflectionTestUtils.setField(service, "profileTtlDays", 30);
    }

    @Test
    @DisplayName("question 为 null/空白时不保存任何记忆")
    void saveConversation_blankQuestion_skips() {
        service.saveConversation("treehole", 1L, null, "answer");
        service.saveConversation("treehole", 1L, "  ", "answer");

        verify(memoryStore, never()).pushRightAndTrim(anyString(), anyString(), any(Integer.class), any());
        verify(vectorStoreService, never()).insertChunks(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("保存对话：写入短期记忆并写入长期向量")
    void saveConversation_normal_savesBothLayers() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        service.saveConversation("treehole", 1L, "最近好吗", "我很好");

        ArgumentCaptor<String> entryCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryStore).pushRightAndTrim(eq("memory:treehole:1"), entryCaptor.capture(),
                eq(5), eq(Duration.ofHours(24)));
        assertTrue(entryCaptor.getValue().contains("最近好吗"));
        assertTrue(entryCaptor.getValue().contains("我很好"));
        verify(vectorStoreService).ensureCollection(-1L);
        verify(vectorStoreService).insertChunks(eq(-1L), eq(-1L), any(), contains("treehole|1|"));
    }

    @Test
    @DisplayName("无短期存储时不写短期、只写长期（各自降级）")
    void saveConversation_noMemoryStore_onlyLongTerm() {
        ConversationMemoryService noStore = new ConversationMemoryService(new ObjectMapper());
        noStore.setEmbeddingService(embeddingService);
        noStore.setVectorStoreService(vectorStoreService);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});

        noStore.saveConversation("chat", 2L, "q", "a");

        verify(vectorStoreService).insertChunks(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("无长期存储（向量/embedding）时只写短期")
    void saveConversation_noVectorStore_onlyShortTerm() {
        service.saveConversation("chat", 2L, "q", "a");
        verify(memoryStore).pushRightAndTrim(anyString(), anyString(), any(Integer.class), any());
        verify(vectorStoreService, never()).insertChunks(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("无任何记忆时构建上下文返回 null")
    void buildMemoryContext_empty_returnsNull() {
        when(memoryStore.range(anyString())).thenReturn(List.of());
        when(memoryStore.get(anyString())).thenReturn(null);
        when(vectorStoreService.search(any(), anyString(), any(Integer.class))).thenReturn(List.of());

        String ctx = service.buildMemoryContext("treehole", 1L, "q");

        assertNull(ctx);
    }

    @Test
    @DisplayName("有短期记忆时上下文包含【最近对话】")
    void buildMemoryContext_shortTerm_includesRecent() throws Exception {
        String entry = new ObjectMapper().writeValueAsString(
                Map.of("question", "最近好吗", "answer", "很好",
                        "timestamp", System.currentTimeMillis() - 120_000));
        when(memoryStore.range("memory:treehole:1")).thenReturn(List.of(entry));
        when(memoryStore.get(anyString())).thenReturn(null);
        when(vectorStoreService.search(any(), anyString(), any(Integer.class))).thenReturn(List.of());

        String ctx = service.buildMemoryContext("treehole", 1L, "q");

        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("用户: 最近好吗"));
        assertTrue(ctx.contains("AI: 很好"));
        // 短期记忆不附带时间描述
        assertTrue(!ctx.contains("分钟前"));
    }

    @Test
    @DisplayName("长期记忆按阈值过滤并拼入【相关历史记忆】")
    void buildMemoryContext_longTerm_filtersByThreshold() {
        VectorStoreLegacy.SearchResult above = new VectorStoreLegacy.SearchResult(
                "用户: 想换个工作\nAI: 可以聊聊", "treehole|1|" + (System.currentTimeMillis() - 3_600_000),
                1L, 0.9f, 0);
        VectorStoreLegacy.SearchResult below = new VectorStoreLegacy.SearchResult(
                "用户: 今天天气\nAI: 晴朗", "treehole|1|" + System.currentTimeMillis(),
                2L, 0.3f, 0);
        when(memoryStore.range(anyString())).thenReturn(List.of());
        when(memoryStore.get(anyString())).thenReturn(null);
        when(vectorStoreService.search(eq(-1L), eq("q"), eq(3))).thenReturn(List.of(above, below));

        String ctx = service.buildMemoryContext("treehole", 1L, "q");

        assertTrue(ctx.contains("【相关历史记忆】"));
        assertTrue(ctx.contains("想换个工作"));
        assertTrue(ctx.contains("1小时前"));
        // 低于阈值 0.5 的条目被过滤
        assertTrue(!ctx.contains("今天天气"));
    }

    @Test
    @DisplayName("长期检索抛异常时降级返回 null")
    void buildMemoryContext_longTermThrows_fallsBack() {
        when(memoryStore.range(anyString())).thenReturn(List.of());
        when(memoryStore.get(anyString())).thenReturn(null);
        when(vectorStoreService.search(any(), anyString(), any(Integer.class)))
                .thenThrow(new RuntimeException("milvus down"));

        String ctx = service.buildMemoryContext("treehole", 1L, "q");

        assertNull(ctx);
    }

    @Test
    @DisplayName("有用户画像时上下文包含【用户画像】")
    void buildMemoryContext_profile_includesProfile() throws Exception {
        String profile = new ObjectMapper().writeValueAsString(Map.of(
                "scene", "在找工作",
                "emotions", List.of("焦虑"),
                "preferences", List.of("先共情再给建议"),
                "contexts", List.of("准备转行")));
        when(memoryStore.get("user_profile:treehole:1")).thenReturn(profile);
        when(memoryStore.range(anyString())).thenReturn(List.of());
        when(vectorStoreService.search(any(), anyString(), any(Integer.class))).thenReturn(List.of());

        String ctx = service.buildMemoryContext("treehole", 1L, "q");

        assertTrue(ctx.contains("【用户画像】"));
        assertTrue(ctx.contains("用户当前情景：在找工作"));
        assertTrue(ctx.contains("用户近期情绪：焦虑"));
        assertTrue(ctx.contains("用户偏好：先共情再给建议"));
        assertTrue(ctx.contains("背景信息：准备转行"));
    }

    @Test
    @DisplayName("画像更新：LLM 提炼 → 合并 → 写 KV → 落库")
    void updateUserProfile_normal_persists() {
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"scene\":\"在找工作\",\"emotions\":[\"焦虑\"],\"preferences\":[\"先共情再给建议\"],\"context\":\"准备转行\"}",
                "qwen", "qwen-plus"));
        when(memoryStore.get(anyString())).thenReturn(null);

        service.updateUserProfile("treehole", 1L, "我最近想换工作很焦虑", "可以聊聊你的计划");

        ArgumentCaptor<String> profileCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryStore).set(eq("user_profile:treehole:1"), profileCaptor.capture(), any());
        String saved = profileCaptor.getValue();
        assertTrue(saved.contains("在找工作"));
        assertTrue(saved.contains("焦虑"));
        assertTrue(saved.contains("先共情再给建议"));
        verify(userProfileRepository).upsert(any(UserProfile.class));
    }

    @Test
    @DisplayName("画像更新：旧画像存在时合并去重，旧偏好保留")
    void updateUserProfile_merge_deduplicates() throws Exception {
        String oldProfile = new ObjectMapper().writeValueAsString(Map.of(
                "scene", "旧情景", "emotions", List.of("平静"),
                "preferences", List.of("语言温柔", "先共情再给建议")));
        when(memoryStore.get(anyString())).thenReturn(oldProfile);
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"scene\":\"新情景\",\"emotions\":[\"焦虑\"],\"preferences\":[\"先共情再给建议\",\"给出建议\"],\"context\":\"新背景\"}",
                "qwen", "qwen-plus"));

        service.updateUserProfile("treehole", 1L, "q", "a");

        ArgumentCaptor<String> profileCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryStore).set(eq("user_profile:treehole:1"), profileCaptor.capture(), any());
        String saved = profileCaptor.getValue();
        assertTrue(saved.contains("新情景"));          // 情景取最新
        assertTrue(saved.contains("焦虑"));            // 情绪取最新
        assertTrue(saved.contains("语言温柔"));        // 旧偏好保留
        assertTrue(saved.contains("给出建议"));        // 新偏好合并
        assertTrue(saved.contains("新背景"));          // 背景追加
    }

    @Test
    @DisplayName("画像更新：LLM 返回失败时不写 KV 不落库")
    void updateUserProfile_llmFail_skips() {
        when(llmInvokeService.invoke(any())).thenReturn(
                LangChainResponse.fail("timeout", "qwen"));

        service.updateUserProfile("treehole", 1L, "q", "a");

        verify(memoryStore, never()).set(anyString(), anyString(), any());
        verify(userProfileRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("画像更新：LLM 输出非 JSON 时跳过")
    void updateUserProfile_nonJson_skips() {
        when(llmInvokeService.invoke(any())).thenReturn(
                LangChainResponse.ok("抱歉我无法分析", "qwen", "qwen-plus"));

        service.updateUserProfile("treehole", 1L, "q", "a");

        verify(memoryStore, never()).set(anyString(), anyString(), any());
        verify(userProfileRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("画像更新：无 LLM 或无 KV 存储时跳过")
    void updateUserProfile_missingDeps_skips() {
        ConversationMemoryService noLlm = new ConversationMemoryService(new ObjectMapper());
        noLlm.setMemoryStore(memoryStore);
        noLlm.updateUserProfile("treehole", 1L, "q", "a");
        verify(memoryStore, never()).set(anyString(), anyString(), any());

        ConversationMemoryService noStore = new ConversationMemoryService(new ObjectMapper());
        noStore.setLlmInvokeService(llmInvokeService);
        noStore.updateUserProfile("treehole", 1L, "q", "a");
        verify(llmInvokeService, never()).invoke(any());
    }

    @Test
    @DisplayName("画像更新：落库失败不影响 KV 写入")
    void updateUserProfile_persistFails_kvStillWritten() {
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"scene\":\"在找工作\"}", "qwen", "qwen-plus"));
        when(memoryStore.get(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("db error"))
                .when(userProfileRepository).upsert(any(UserProfile.class));

        service.updateUserProfile("treehole", 1L, "q", "a");

        verify(memoryStore).set(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("清除短期记忆调用 KV delete")
    void clearShortTerm_deletes() {
        service.clearShortTerm("treehole", 1L);
        verify(memoryStore).delete("memory:treehole:1");
    }

    @Test
    @DisplayName("Redis miss 时从 DB 恢复画像并回填 KV")
    void buildMemoryContext_dbProfileBackfill() {
        UserProfile dbProfile = new UserProfile();
        dbProfile.userId = 1L;
        dbProfile.scene = "treehole";
        dbProfile.sceneDesc = "正在备考";
        dbProfile.emotionsJson = "[\"紧张\"]";
        dbProfile.preferencesJson = "[]";
        dbProfile.contextsJson = "[]";
        when(memoryStore.get(anyString())).thenReturn(null);
        when(userProfileRepository.findByUserIdAndScene(1L, "treehole")).thenReturn(dbProfile);
        when(memoryStore.range(anyString())).thenReturn(List.of());
        when(vectorStoreService.search(any(), anyString(), any(Integer.class))).thenReturn(List.of());

        String ctx = service.buildMemoryContext("treehole", 1L, "q");

        assertTrue(ctx.contains("【用户画像】"));
        assertTrue(ctx.contains("正在备考"));
        assertTrue(ctx.contains("紧张"));
        verify(memoryStore).set(eq("user_profile:treehole:1"), anyString(), any());
    }

    @Test
    @DisplayName("上下文为空时 equals 校验：无画像/短期/长期则返回 null")
    void buildMemoryContext_allEmpty_returnsNull() {
        when(memoryStore.range(anyString())).thenReturn(List.of());
        when(memoryStore.get(anyString())).thenReturn(null);
        when(vectorStoreService.search(any(), anyString(), any(Integer.class))).thenReturn(List.of());

        assertNull(service.buildMemoryContext("treehole", 1L, "q"));
    }
}
