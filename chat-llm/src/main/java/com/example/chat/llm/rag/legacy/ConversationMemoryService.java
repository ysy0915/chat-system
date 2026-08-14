package com.example.chat.llm.rag.legacy;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.entity.UserProfile;
import com.example.chat.llm.service.LLMInvokeService;
import com.example.chat.repository.UserProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 对话记忆服务
 *
 * 两层记忆架构：
 *   1. 短期记忆（Redis）：最近 N 轮对话，完整保留，快速读取
 *   2. 长期记忆（Milvus）：所有历史对话向量化，按相关性检索
 *
 * 使用方式（业务层调用）：
 *   // 保存一轮对话
 *   memoryService.saveConversation("treehole", userId, question, answer);
 *
 *   // 构建记忆上下文（拼入 prompt）
 *   String memory = memoryService.buildMemoryContext("treehole", userId, question);
 *   // memory 格式：
 *   //   【最近对话】
 *   //   用户: xxx
 *   //   AI: xxx
 *   //   【相关历史】
 *   //   用户(3天前): xxx
 *   //   AI: xxx
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
@SuppressWarnings("PMD.CyclomaticComplexity") // 类级复杂度来自字段初始化器/流式匿名类，业务方法已分别豁免
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    /** 短期记忆 + 用户画像 KV 存储（Redis 或纯内存，standalone 无 Redis 自动切换） */
    private MemoryKVStore memoryStore;
    private LegacyEmbeddingService embeddingService;
    private VectorStoreLegacy vectorStoreService;
    private LLMInvokeService llmInvokeService;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.memory.short-term-rounds:5}")
    private int shortTermRounds;

    @Value("${app.rag.memory.long-term-top-k:3}")
    private int longTermTopK;

    @Value("${app.rag.memory.long-term-threshold:0.5}")
    private float longTermThreshold;

    @Value("${app.rag.memory.redis-ttl-hours:24}")
    private int redisTtlHours;

    @Value("${app.rag.memory.profile-ttl-days:30}")
    private int profileTtlDays;

    @Value("${app.rag.memory.profile-provider:qwen}")
    private String profileProvider;

    @Value("${app.rag.memory.profile-model:qwen-plus}")
    private String profileModel;

    /** Milvus 中对话记忆的 Collection 名 */
    private static final String MEMORY_COLLECTION = "conversation_memory";

    /** 用户画像 Redis key 前缀 */
    private static final String PROFILE_KEY_PREFIX = "user_profile:";

    @Autowired
    public ConversationMemoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setMemoryStore(MemoryKVStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Autowired(required = false)
    public void setEmbeddingService(LegacyEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Autowired(required = false)
    public void setVectorStoreService(VectorStoreLegacy vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    @Autowired(required = false)
    public void setLlmInvokeService(LLMInvokeService llmInvokeService) {
        this.llmInvokeService = llmInvokeService;
    }

    /** 用户画像持久化（MySQL user_profiles 表，L3） */
    private UserProfileRepository userProfileRepository;

    @Autowired(required = false)
    public void setUserProfileRepository(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    // ==================== 保存对话 ====================

    /**
     * 保存一轮对话到短期 + 长期记忆
     *
     * @param scene    场景（treehole / personal / chat）
     * @param userId   用户 ID
     * @param question 用户问题
     * @param answer   AI 回答
     */
    public void saveConversation(String scene, Long userId, String question, String answer) {
        if (question == null || question.isBlank()) return;

        // 1. 存短期记忆（Redis）
        saveToShortTerm(scene, userId, question, answer);

        // 2. 存长期记忆（Milvus）
        saveToLongTerm(scene, userId, question, answer);
    }

    private void saveToShortTerm(String scene, Long userId, String question, String answer) {
        if (memoryStore == null) return;
        try {
            String key = getShortTermKey(scene, userId);
            String entry = objectMapper.writeValueAsString(Map.of(
                    "question", question,
                    "answer", answer != null ? answer : "",
                    "timestamp", System.currentTimeMillis()
            ));
            // 追加并只保留最近 N 轮（Redis: RLPUSH+LTRIM；内存: 列表裁剪）
            memoryStore.pushRightAndTrim(key, entry, shortTermRounds, Duration.ofHours(redisTtlHours));
        } catch (Exception e) {
            log.warn("[Memory] 短期记忆保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    private void saveToLongTerm(String scene, Long userId, String question, String answer) {
        if (vectorStoreService == null || embeddingService == null) return;
        try {
            ensureMemoryCollection();

            // 把整轮对话（问+答）作为一个向量
            String combined = "用户: " + question + "\nAI: " + (answer != null ? answer : "");
            float[] vector = embeddingService.embed(combined);

            // 构建插入数据
            List<Float> vec = new ArrayList<>();
            for (float v : vector) vec.add(v);

            List<VectorStoreLegacy.ChunkText> chunks = List.of(
                    new VectorStoreLegacy.ChunkText(combined, 0)
            );
            // 用固定 kbId=-1 表示记忆库，source 记录场景和时间
            String source = scene + "|" + userId + "|" + System.currentTimeMillis();
            vectorStoreService.insertChunks(-1L, -1L, chunks, source);

            log.info("[Memory] 长期记忆保存成功 scene={} user={} qLen={} aLen={}",
                    scene, userId, question.length(), answer != null ? answer.length() : 0);
        } catch (Exception e) {
            log.warn("[Memory] 长期记忆保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    // ==================== 构建记忆上下文 ====================

    /**
     * 构建记忆上下文，拼入 prompt 的 system 消息中
     *
     * @param scene       场景
     * @param userId      用户 ID
     * @param currentQuestion 当前问题（用于检索相关长期记忆）
     * @return 记忆上下文字符串（无记忆时返回 null）
     */
    public String buildMemoryContext(String scene, Long userId, String currentQuestion) {
        // 0. 读取用户画像（情景 / 情绪 / 个人偏好）
        String profile = getUserProfileContext(scene, userId);

        // 1. 获取短期记忆（最近 N 轮）
        List<ConversationEntry> shortTerm = getShortTerm(scene, userId);

        // 2. 检索长期记忆（与当前问题相关的历史对话）
        List<ConversationEntry> longTerm = getLongTerm(scene, userId, currentQuestion);

        if (profile == null && shortTerm.isEmpty() && longTerm.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是你的记忆信息，请参考但不要逐字重复：\n\n");

        if (profile != null) {
            sb.append("【用户画像】\n").append(profile).append("\n\n");
        }

        if (!shortTerm.isEmpty()) {
            sb.append("【最近对话】\n");
            for (ConversationEntry e : shortTerm) {
                sb.append("用户: ").append(e.question).append('\n');
                sb.append("AI: ").append(e.answer).append('\n').append('\n');
            }
        }

        if (!longTerm.isEmpty()) {
            sb.append("【相关历史记忆】\n");
            for (ConversationEntry e : longTerm) {
                sb.append("用户(").append(e.timeDesc).append("): ").append(e.question).append('\n');
                sb.append("AI: ").append(e.answer).append('\n').append('\n');
            }
        }

        return sb.toString();
    }

    private List<ConversationEntry> getShortTerm(String scene, Long userId) {
        if (memoryStore == null) return Collections.emptyList();
        try {
            String key = getShortTermKey(scene, userId);
            List<String> entries = memoryStore.range(key);
            if (entries == null || entries.isEmpty()) return Collections.emptyList();

            List<ConversationEntry> result = new ArrayList<>();
            for (String entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = objectMapper.readValue(entry, Map.class);
                result.add(new ConversationEntry(
                        (String) m.get("question"),
                        (String) m.get("answer"),
                        formatTimeAgo(System.currentTimeMillis() - ((Number) m.get("timestamp")).longValue())
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("[Memory] 短期记忆读取失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ConversationEntry> getLongTerm(String scene, Long userId, String query) {
        if (vectorStoreService == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            ensureMemoryCollection();
            List<VectorStoreLegacy.SearchResult> results =
                    vectorStoreService.search(-1L, query, longTermTopK);

            List<ConversationEntry> result = new ArrayList<>();
            for (VectorStoreLegacy.SearchResult r : results) {
                if (r.score < longTermThreshold) continue;

                // 解析对话内容（格式: "用户: xxx\nAI: xxx"）
                String[] parts = r.text.split("\nAI: ", 2);
                String question = parts[0].replaceFirst("^用户: ", "");
                String answer = parts.length > 1 ? parts[1] : "";

                // 解析时间和场景
                String timeDesc = "之前";
                if (r.source != null && r.source.contains("|")) {
                    String[] meta = r.source.split("\\|");
                    if (meta.length >= 3) {
                        try {
                            long ts = Long.parseLong(meta[2]);
                            timeDesc = formatTimeAgo(System.currentTimeMillis() - ts);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                result.add(new ConversationEntry(question, answer, timeDesc));
            }
            return result;
        } catch (Exception e) {
            log.warn("[Memory] 长期记忆检索失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 工具方法 ====================

    private String getShortTermKey(String scene, Long userId) {
        return "memory:" + scene + ":" + userId;
    }

    private void ensureMemoryCollection() {
        if (vectorStoreService == null) return;
        // 用 kbId=-1 作为记忆库的 Collection
        vectorStoreService.ensureCollection(-1L);
    }

    private String formatTimeAgo(long millis) {
        long minutes = millis / 60000;
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + "分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时前";
        long days = hours / 24;
        if (days < 30) return days + "天前";
        return days / 30 + "个月前";
    }

    // ==================== 用户画像（情景/情绪/偏好） ====================

    /**
     * 更新用户画像（供调用方在保存对话后异步触发，失败不阻塞主流程）。
     *
     * <p>用 LLM 从本轮对话中提炼用户的情景 / 情绪 / 个人偏好，
     * 与既有画像合并后写入 Redis（key: user_profile:{scene}:{userId}），
     * 后续 buildMemoryContext 会把画像注入记忆上下文，让回答贴合用户偏好。</p>
     */
    public void updateUserProfile(String scene, Long userId, String question, String answer) {
        if (llmInvokeService == null || memoryStore == null) return;
        if (question == null || question.isBlank()) return;
        try {
            Map<String, Object> extracted = extractUserProfile(question, answer);
            if (extracted == null || extracted.isEmpty()) return;

            Map<String, Object> merged = mergeProfile(readProfile(scene, userId), extracted);
            memoryStore.set(getProfileKey(scene, userId),
                    objectMapper.writeValueAsString(merged),
                    Duration.ofDays(profileTtlDays));
            // L3: 画像持久化到 MySQL user_profiles（Redis 为主缓存，MySQL 保底不丢）
            persistProfile(scene, userId, merged);
            log.info("[Memory] 用户画像已更新 scene={} user={} emotions={} preferences={}",
                    scene, userId,
                    joinList(merged.get("emotions"), "、"),
                    joinList(merged.get("preferences"), "；"));
        } catch (Exception e) {
            log.warn("[Memory] 用户画像更新失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    /**
     * 调用 LLM 从一轮对话中提炼用户画像（情景 / 情绪 / 偏好）。
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null 表示"提炼失败/无画像"，调用方依赖 null 判断
    private Map<String, Object> extractUserProfile(String question, String answer) {
        String systemPrompt =
                "你是一名用户心理画像分析师，任务是从用户的情感倾诉中提炼用户画像。\n" +
                "仅输出 JSON 对象，不要输出任何解释或额外文字，格式如下：\n" +
                "{\n" +
                "  \"scene\": \"一句话描述用户当前的情景\",\n" +
                "  \"emotions\": [\"情绪1\", \"情绪2\"],\n" +
                "  \"preferences\": [\"用户偏好的回应方式1\", \"偏好2\"],\n" +
                "  \"context\": \"补充背景信息，不超过50字\"\n" +
                "}\n" +
                "规则：\n" +
                "1. emotions 只提炼用户当前表达的情绪，最多3个；\n" +
                "2. preferences 指用户喜欢的回应风格或需求（如：先共情再给建议、语言温柔、称呼亲切、希望得到具体可操作的建议等），最多3条；\n" +
                "3. 如果信息不足以提炼某一项，用空数组或空字符串。";

        String userContent = "用户的倾诉：\n" + question +
                "\n\n你（树洞）的回应：\n" + (answer != null ? answer : "");

        LangChainRequest req = new LangChainRequest();
        req.setBizType("RAG");
        req.setProvider(profileProvider);
        req.setModel(profileModel);
        req.setTemperature(0.2);
        req.setMaxTokens(500);
        req.setMessages(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));

        LangChainResponse resp = llmInvokeService.invoke(req);
        if (!resp.isSuccess() || resp.getContent() == null || resp.getContent().isBlank()) {
            log.warn("[Memory] 画像提炼失败 provider={} error={}", profileProvider, resp.getError());
            return null;
        }
        return parseProfileJson(resp.getContent());
    }

    @SuppressWarnings({"unchecked", "PMD.ReturnEmptyCollectionRatherThanNull"}) // null 表示"无画像"，调用方依赖 null 判断
    private Map<String, Object> parseProfileJson(String content) {
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("[Memory] 画像输出非 JSON: {}", truncate(content, 100));
            return null;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(json.substring(start, end + 1), Map.class);
            return m != null ? m : null;
        } catch (Exception e) {
            log.warn("[Memory] 画像 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProfile(String scene, Long userId) {
        if (memoryStore == null) return readProfileFromDb(scene, userId);
        try {
            String raw = memoryStore.get(getProfileKey(scene, userId));
            if (raw == null || raw.isBlank()) {
                // 无画像（可能过期）：从 MySQL 恢复并回填 KV
                Map<String, Object> fromDb = readProfileFromDb(scene, userId);
                if (!fromDb.isEmpty()) {
                    try {
                        memoryStore.set(getProfileKey(scene, userId),
                                objectMapper.writeValueAsString(fromDb),
                                Duration.ofDays(profileTtlDays));
                    } catch (Exception ignored) {
                    }
                }
                return fromDb;
            }
            Map<String, Object> m = objectMapper.readValue(raw, Map.class);
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("[Memory] 画像读取失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * L3: 将合并后的画像写入 MySQL user_profiles（幂等 upsert）。
     */
    private void persistProfile(String scene, Long userId, Map<String, Object> profile) {
        if (userProfileRepository == null || profile == null || profile.isEmpty()) return;
        try {
            UserProfile p = new UserProfile();
            p.userId = userId;
            p.scene = scene;
            p.sceneDesc = str(profile.get("scene"));
            p.emotionsJson = toJsonArray(profile.get("emotions"));
            p.preferencesJson = toJsonArray(profile.get("preferences"));
            p.contextsJson = toJsonArray(profile.get("contexts"));
            userProfileRepository.upsert(p);
        } catch (Exception e) {
            log.warn("[Memory] 画像落库失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    /**
     * 从 MySQL 读取画像（Redis miss 兜底），恢复为与 Redis 一致的 Map 结构。
     */
    private Map<String, Object> readProfileFromDb(String scene, Long userId) {
        if (userProfileRepository == null) return new LinkedHashMap<>();
        try {
            UserProfile p = userProfileRepository.findByUserIdAndScene(userId, scene);
            if (p == null) return new LinkedHashMap<>();
            Map<String, Object> m = new LinkedHashMap<>();
            if (p.sceneDesc != null && !p.sceneDesc.isBlank()) m.put("scene", p.sceneDesc);
            m.put("emotions", parseJsonArray(p.emotionsJson));
            m.put("preferences", parseJsonArray(p.preferencesJson));
            m.put("contexts", parseJsonArray(p.contextsJson));
            if (p.updatedAt != null) m.put("updatedAt", p.updatedAt.toEpochMilli());
            return m;
        } catch (Exception e) {
            log.warn("[Memory] 画像 DB 读取失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String toJsonArray(Object value) {
        try {
            if (value instanceof List<?> list) {
                return objectMapper.writeValueAsString(list);
            }
        } catch (Exception ignored) {
        }
        return "[]";
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<Object> list = objectMapper.readValue(json, List.class);
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                String s = str(o);
                if (!s.isBlank()) result.add(s);
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 合并新旧画像：情景/情绪取最新，偏好增量合并去重，背景追加最新。
     */
    @SuppressWarnings({"unchecked", "PMD.NPathComplexity", "PMD.ConfusingTernary"})
    // 画像合并按字段类型分别取最新/合并去重，分支对称直白，拆分会引入大量中间 Map
    private Map<String, Object> mergeProfile(Map<String, Object> oldProfile, Map<String, Object> newProfile) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 情景：取最新
        String newScene = str(newProfile.get("scene"));
        if (!newScene.isBlank()) {
            merged.put("scene", newScene);
        } else if (oldProfile.get("scene") != null) {
            merged.put("scene", oldProfile.get("scene"));
        }

        // 情绪：取最新
        List<String> newEmotions = strList(newProfile.get("emotions"));
        if (!newEmotions.isEmpty()) {
            merged.put("emotions", newEmotions);
        } else if (oldProfile.get("emotions") != null) {
            merged.put("emotions", oldProfile.get("emotions"));
        }

        // 偏好：合并去重，最多保留 10 条
        List<String> prefs = new ArrayList<>(strList(oldProfile.get("preferences")));
        for (String p : strList(newProfile.get("preferences"))) {
            if (!prefs.contains(p)) prefs.add(p);
        }
        if (!prefs.isEmpty()) {
            merged.put("preferences", prefs.size() > 10 ? prefs.subList(0, 10) : prefs);
        }

        // 背景：追加最新一条，保留最近 5 条
        List<String> contexts = new ArrayList<>(strList(oldProfile.get("contexts")));
        String newCtx = str(newProfile.get("context"));
        if (!newCtx.isBlank()) contexts.add(newCtx);
        if (!contexts.isEmpty()) {
            merged.put("contexts",
                    contexts.size() > 5 ? contexts.subList(contexts.size() - 5, contexts.size()) : contexts);
        }

        merged.put("updatedAt", System.currentTimeMillis());
        return merged;
    }

    /**
     * 将画像渲染为记忆上下文中【用户画像】段落。
     */
    private String getUserProfileContext(String scene, Long userId) {
        Map<String, Object> profile = readProfile(scene, userId);
        if (profile.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        String s = str(profile.get("scene"));
        if (!s.isBlank()) sb.append("用户当前情景：").append(s).append('\n');
        String emotions = joinList(profile.get("emotions"), "、");
        if (!emotions.isBlank()) sb.append("用户近期情绪：").append(emotions).append('\n');
        String prefs = joinList(profile.get("preferences"), "；");
        if (!prefs.isBlank()) sb.append("用户偏好：").append(prefs).append('\n');
        String contexts = joinList(profile.get("contexts"), "；");
        if (!contexts.isBlank()) sb.append("背景信息：").append(contexts).append('\n');
        sb.append("请根据用户画像中的情绪与偏好调整你的回应风格，让回复更贴合用户需求。");
        return sb.toString();
    }

    private String getProfileKey(String scene, Long userId) {
        return PROFILE_KEY_PREFIX + scene + ":" + userId;
    }

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private String joinList(Object value, String delimiter) {
        if (!(value instanceof List<?> list)) return "";
        return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.joining(delimiter));
    }

    private List<String> strList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object o : list) {
            String s = str(o);
            if (!s.isBlank() && !result.contains(s)) result.add(s);
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 清除记忆 ====================

    /**
     * 清除用户的短期记忆（Redis）
     */
    public void clearShortTerm(String scene, Long userId) {
        if (memoryStore == null) return;
        memoryStore.delete(getShortTermKey(scene, userId));
    }

    // ==================== 数据结构 ====================

    private static class ConversationEntry {
        final String question;
        final String answer;
        final String timeDesc;

        ConversationEntry(String question, String answer, String timeDesc) {
            this.question = question;
            this.answer = answer;
            this.timeDesc = timeDesc;
        }
    }
}
