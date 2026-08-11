package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentResult;
import com.example.chat.intent.IntentCategory;
import com.example.chat.rag.service.EmbeddingService;
import com.example.chat.rag.service.VectorStoreService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 意图漏斗 — 第二层：上下文语义匹配。
 *
 * <pre>
 *   用 embedding + Milvus 做语义级意图识别。
 *   维护 intent_examples 向量集合，每轮对话 embed → k-NN → 投票出最高意图。
 *
 *   速度：~30-80ms（embedding + Milvus 检索）
 *   命中率目标：80-90%
 *
 *   新增优化：
 *   - 启动时预加载 ~420 条种子示例
 *   - 滑动窗口动态阈值调优
 *   - 增强上下文消歧（多轮同主题 + 代码场景识别）
 *   - nprobe 按集合大小自适应
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class ContextMatcher {

    private static final Logger log = LoggerFactory.getLogger(ContextMatcher.class);
    private static final String COLLECTION_NAME = "intent_examples";
    private static final int DIM = 1024; // text-embedding-v3 维度

    @Autowired(required = false)
    private MilvusServiceClient milvusClient;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    /** 默认相似度阈值（低于此分值的舍弃） */
    private volatile double matchThreshold = 0.75;

    /** 检索 topK */
    private volatile int topK = 5;

    /** collection 是否已就绪（懒初始化） */
    private volatile boolean collectionReady;

    // ────── 意图类别置信度权重映射 ──────
    private static final Map<String, Double> CATEGORY_THRESHOLD_OVERRIDE = new ConcurrentHashMap<>();
    static {
        CATEGORY_THRESHOLD_OVERRIDE.put("GENERAL_CHAT", 0.72);
        CATEGORY_THRESHOLD_OVERRIDE.put("EMOTIONAL_SUPPORT", 0.70);
        CATEGORY_THRESHOLD_OVERRIDE.put("TRANSLATION", 0.80);
        CATEGORY_THRESHOLD_OVERRIDE.put("CODE_GENERATION", 0.80);
    }

    // ────── 动态阈值调优: 滑动窗口 ──────
    /** 每意图最近1000次匹配的滑动窗口：命中次数 */
    private final Map<String, SlidingHitWindow> hitWindows = new ConcurrentHashMap<>();
    private static final int WINDOW_SIZE = 1000;
    private static final double ADJUST_STEP = 0.02;
    private static final double HIT_RATE_UPPER = 0.85;  // 命中率高于此 → 降低阈值扩大覆盖
    private static final double HIT_RATE_LOWER = 0.70;  // 命中率低于此 → 提高阈值减少误判

    // ────── 质量监控指标 ──────
    private final AtomicLong totalMatchCalls = new AtomicLong();
    private final AtomicLong totalHitCount = new AtomicLong();
    private final AtomicLong totalMissCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> categoryHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> categoryMisses = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════
    //  生命周期：启动后加载种子数据
    // ═══════════════════════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        loadSeedExamples();
    }

    /**
     * 从 intent-seeds.json 预加载种子示例到 Milvus。
     * 仅在 collection 为空时写入（避免重启重复灌入）。
     */
    public void loadSeedExamples() {
        if (milvusClient == null || embeddingService == null) {
            log.info("[ContextMatcher] Milvus/Embedding 不可用，跳过种子加载");
            return;
        }
        if (!initCollection()) {
            log.warn("[ContextMatcher] collection 初始化失败，跳过种子加载");
            return;
        }

        // 先检查已有数量，避免重复灌入
        long existing = countExamples();
        if (existing > 50) {
            log.info("[ContextMatcher] collection 已有 {} 条示例，跳过种子加载", existing);
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource("intent-seeds.json");
            if (!resource.exists()) {
                log.warn("[ContextMatcher] intent-seeds.json 不存在，跳过种子加载");
                return;
            }

            List<SeedEntry> seeds;
            try (InputStream is = resource.getInputStream()) {
                seeds = objectMapper.readValue(is,
                        new TypeReference<List<SeedEntry>>() {});
            }

            if (seeds == null || seeds.isEmpty()) {
                log.warn("[ContextMatcher] intent-seeds.json 为空");
                return;
            }

            // 转换为 insertExamples 的格式
            List<Map.Entry<String, String>> examples = new ArrayList<>();
            for (SeedEntry seed : seeds) {
                if (seed.getText() != null && !seed.getText().isBlank()
                        && seed.getIntent() != null) {
                    examples.add(new AbstractMap.SimpleEntry<>(seed.getText(), seed.getIntent()));
                }
            }

            // 分批插入（DashScope 原生 API 限制 10 条/次，兼容模式 25 条/次）
            int batchSize = 10;
            int total = 0;
            for (int i = 0; i < examples.size(); i += batchSize) {
                int end = Math.min(i + batchSize, examples.size());
                insertExamples(examples.subList(i, end));
                total += (end - i);
            }

            log.info("[ContextMatcher] 种子数据加载完成: {} 条，共 {} 批次",
                     total, (int) Math.ceil(examples.size() / (double) batchSize));
        } catch (Exception e) {
            log.error("[ContextMatcher] 种子数据加载失败: {}", e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对外接口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 上下文语义匹配。
     *
     * @param text        用户输入
     * @param lastIntents 最近 N 轮意图（用于消歧）
     * @return 匹配结果，未命中返回 empty
     */
    public Optional<IntentResult> match(String text, List<String> lastIntents) {
        if (text == null || text.isBlank()) return Optional.empty();
        if (milvusClient == null || embeddingService == null) {
            log.warn("[ContextMatcher] Milvus/Embedding 不可用，跳过");
            return Optional.empty();
        }
        if (!initCollection()) return Optional.empty();

        totalMatchCalls.incrementAndGet();

        try {
            // 1) embed
            float[] vec = embeddingService.embed(text);
            List<Float> queryVec = new ArrayList<>(vec.length);
            for (float v : vec) queryVec.add(v);

            // 2) k-NN 检索（nprobe 动态调整）
            long collectionSize = countExamples();
            int nprobe = computeNprobe(collectionSize);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topK)
                    .withVectors(List.of(queryVec))
                    .withVectorFieldName("embedding")
                    .withOutFields(List.of("text", "intent"))
                    .withParams("{\"nprobe\":" + nprobe + "}")
                    .build();

            SearchResults response = milvusClient.search(searchParam).getData();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getResults());

            // 3) 投票 + 阈值判断
            Map<String, Vote> votes = new LinkedHashMap<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                SearchResultsWrapper.IDScore score = wrapper.getIDScore(0).get(i);
                String intent = wrapper.getFieldData("intent", 0).get(i).toString();
                String exampleText = wrapper.getFieldData("text", 0).get(i).toString();
                float cosScore = score.getScore();

                votes.computeIfAbsent(intent, k -> new Vote())
                     .add(cosScore, exampleText);
            }

            if (votes.isEmpty()) {
                totalMissCount.incrementAndGet();
                return Optional.empty();
            }

            // 取最高分
            Map.Entry<String, Vote> best = votes.entrySet().stream()
                    .max((a, b) -> Double.compare(a.getValue().maxScore, b.getValue().maxScore))
                    .orElse(null);
            if (best == null) {
                totalMissCount.incrementAndGet();
                return Optional.empty();
            }

            double bestScore = best.getValue().maxScore;
            double threshold = getDynamicThreshold(best.getKey());

            // 4) 增强上下文消歧
            double adjustedScore = adjustScoreWithContext(best.getKey(), bestScore, lastIntents, text);

            if (adjustedScore < threshold) {
                log.debug("[ContextMatcher] top-intent={} adjustedScore={:.3f} < threshold={:.3f} → no match",
                         best.getKey(), adjustedScore, threshold);
                totalMissCount.incrementAndGet();
                recordCategoryMiss(best.getKey());
                return Optional.empty();
            }

            // 5) 记录命中 + 动态阈值更新
            totalHitCount.incrementAndGet();
            recordCategoryHit(best.getKey());
            updateSlidingWindow(best.getKey(), true);

            // 6) 构建 IntentResult
            IntentCategory cat = safeParseCategory(best.getKey());
            IntentResult result = new IntentResult(
                    cat,
                    (float) Math.min(adjustedScore, 1.0),
                    "context-match: top=" + best.getKey() + " score=" + String.format("%.3f", adjustedScore)
                    + " nprobe=" + nprobe + " threshold=" + String.format("%.3f", threshold)
                    + " examples=" + best.getValue().count,
                    best.getValue().topExample
            );

            log.info("[ContextMatcher] 命中: intent={} score={:.3f} threshold={:.3f} nprobe={} collectionSize={}",
                     best.getKey(), adjustedScore, threshold, nprobe, collectionSize);
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("[ContextMatcher] 语义匹配异常: {}", e.getMessage());
            totalMissCount.incrementAndGet();
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  意图示例管理
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 批量插入意图示例到 Milvus。
     *
     * @param examples [(text, intentCategory), ...]
     */
    public void insertExamples(List<Map.Entry<String, String>> examples) {
        if (examples == null || examples.isEmpty()) return;
        if (!initCollection()) return;

        List<Long> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> intents = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();

        long baseId = System.currentTimeMillis();
        for (int i = 0; i < examples.size(); i++) {
            String text = examples.get(i).getKey();
            String intent = examples.get(i).getValue();
            if (text == null || text.isBlank() || intent == null) continue;

            float[] vec = embeddingService.embed(text);
            List<Float> vecList = new ArrayList<>(vec.length);
            for (float v : vec) vecList.add(v);

            ids.add(baseId + i);
            texts.add(text);
            intents.add(intent);
            embeddings.add(vecList);
        }

        if (ids.isEmpty()) return;

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", ids));
        fields.add(new InsertParam.Field("text", texts));
        fields.add(new InsertParam.Field("intent", intents));
        fields.add(new InsertParam.Field("embedding", embeddings));

        try {
            milvusClient.insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withFields(fields)
                    .build());
            log.info("[ContextMatcher] 插入 {} 条意图示例", ids.size());
        } catch (Exception e) {
            log.error("[ContextMatcher] 插入意图示例失败: {}", e.getMessage());
        }
    }

    /** 统计当前示例数量 */
    public long countExamples() {
        if (!initCollection()) return 0;
        try {
            var stats = milvusClient.getCollectionStatistics(
                    io.milvus.param.collection.GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(COLLECTION_NAME).build()
            );
            if (stats != null && stats.getData() != null) {
                var data = stats.getData();
                for (var pair : data.getStatsList()) {
                    if ("row_count".equals(pair.getKey())) {
                        return Long.parseLong(pair.getValue());
                    }
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  运维接口
    // ═══════════════════════════════════════════════════════════════════

    /** 完整的质量监控数据 */
    public Map<String, Object> qualityStats() {
        long total = totalMatchCalls.get();
        long hits = totalHitCount.get();
        long misses = totalMissCount.get();

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total_match_calls", total);
        s.put("hits", hits);
        s.put("misses", misses);
        s.put("overall_hit_rate", total > 0
                ? String.format("%.2f%%", 100.0 * hits / total) : "0%");
        s.put("collection_size", countExamples());
        s.put("default_threshold", matchThreshold);

        // 每意图命中率
        Map<String, String> perCategoryRates = new LinkedHashMap<>();
        for (String cat : categoryHits.keySet()) {
            long catHit = categoryHits.getOrDefault(cat, new AtomicLong(0)).get();
            long catMiss = categoryMisses.getOrDefault(cat, new AtomicLong(0)).get();
            long catTotal = catHit + catMiss;
            double rate = catTotal > 0 ? 100.0 * catHit / catTotal : 0;
            perCategoryRates.put(cat, String.format("%.2f%% (%d/%d)", rate, catHit, catTotal));
        }
        s.put("per_category_hit_rate", perCategoryRates);

        // 当前每意图动态阈值
        Map<String, Double> currentThresholds = new LinkedHashMap<>();
        for (var entry : CATEGORY_THRESHOLD_OVERRIDE.entrySet()) {
            currentThresholds.put(entry.getKey(), entry.getValue());
        }
        // 也包含未在 override 中的（用默认值）
        for (String cat : hitWindows.keySet()) {
            currentThresholds.putIfAbsent(cat, matchThreshold);
        }
        s.put("dynamic_thresholds", currentThresholds);

        return s;
    }

    /** 重置滑动窗口和指标 */
    public void resetStats() {
        hitWindows.clear();
        totalMatchCalls.set(0);
        totalHitCount.set(0);
        totalMissCount.set(0);
        categoryHits.clear();
        categoryMisses.clear();
        log.info("[ContextMatcher] 指标已重置");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部：动态阈值
    // ═══════════════════════════════════════════════════════════════════

    /** 获取某意图的动态阈值 */
    private double getDynamicThreshold(String intent) {
        return CATEGORY_THRESHOLD_OVERRIDE.getOrDefault(intent, matchThreshold);
    }

    /** 更新滑动窗口并动态调整阈值 */
    private void updateSlidingWindow(String intent, boolean hit) {
        SlidingHitWindow window = hitWindows.computeIfAbsent(intent,
                k -> new SlidingHitWindow(WINDOW_SIZE));
        window.add(hit);

        // 每 50 次命中检查一次是否需要调整阈值
        long total = window.total();
        if (total < 50 || total % 50 != 0) return;

        double hitRate = window.hitRate();
        double currentThreshold = getDynamicThreshold(intent);

        if (hitRate > HIT_RATE_UPPER && currentThreshold > 0.65) {
            // 命中率太高 → 降低阈值扩大覆盖
            double newThreshold = Math.max(0.65, currentThreshold - ADJUST_STEP);
            CATEGORY_THRESHOLD_OVERRIDE.put(intent, newThreshold);
            log.info("[ContextMatcher] 阈值下调: intent={} {:.3f}→{:.3f} hitRate={:.2f}",
                     intent, currentThreshold, newThreshold, hitRate);
        } else if (hitRate < HIT_RATE_LOWER && currentThreshold < 0.88) {
            // 命中率太低 → 提高阈值减少误判
            double newThreshold = Math.min(0.88, currentThreshold + ADJUST_STEP);
            CATEGORY_THRESHOLD_OVERRIDE.put(intent, newThreshold);
            log.info("[ContextMatcher] 阈值上调: intent={} {:.3f}→{:.3f} hitRate={:.2f}",
                     intent, currentThreshold, newThreshold, hitRate);
        }
    }

    private void recordCategoryHit(String intent) {
        categoryHits.computeIfAbsent(intent, k -> new AtomicLong()).incrementAndGet();
    }

    private void recordCategoryMiss(String intent) {
        categoryMisses.computeIfAbsent(intent, k -> new AtomicLong()).incrementAndGet();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部：动态 nprobe
    // ═══════════════════════════════════════════════════════════════════

    /** 按集合大小自适应 nprobe */
    private int computeNprobe(long collectionSize) {
        if (collectionSize < 1000) return 32;   // 小集合 → 高召回
        if (collectionSize < 5000) return 16;   // 中等集合 → 平衡
        return 8;                                // 大集合 → 优先速度
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部：增强上下文消歧
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 增强版上下文消歧：
     * - 多轮同主题加分
     * - 代码场景中 KNOWLEDGE_QA 降权
     * - 情绪连续加分
     */
    private double adjustScoreWithContext(String matchedIntent, double rawScore,
                                          List<String> lastIntents, String currentText) {
        if (lastIntents == null || lastIntents.isEmpty()) return rawScore;

        // 1) 多轮同主题加分
        long recentSameCount = lastIntents.stream()
                .filter(i -> i.equalsIgnoreCase(matchedIntent))
                .count();
        double bonus = 0;
        if (recentSameCount >= 2) {
            bonus += 0.05;
        } else if (recentSameCount == 1) {
            bonus += 0.02;
        }

        // 2) 代码场景中 "是什么" 类问题可能是语法查询而非知识问答
        if (matchedIntent.equals("KNOWLEDGE_QA") && isCodeContext(lastIntents)) {
            // 在代码上下文中问"是什么"，降低匹配置信度
            if (isDefinitionPattern(currentText)) {
                bonus -= 0.03;
            }
        }

        // 3) 情感连续性：前一轮是 EMOTIONAL_SUPPORT → 本轮也倾向于是情绪相关
        if (matchedIntent.equals("EMOTIONAL_SUPPORT") && lastIntents.size() > 0) {
            String lastIntent = lastIntents.get(0);
            if (lastIntent.equalsIgnoreCase("EMOTIONAL_SUPPORT")
                    || lastIntent.equalsIgnoreCase("GENERAL_CHAT")) {
                bonus += 0.03; // 情绪延续
            }
        }

        // 4) 翻译场景：前轮若是翻译，本轮大概率仍是翻译
        if (matchedIntent.equals("TRANSLATION") && lastIntents.size() > 0) {
            String lastIntent = lastIntents.get(0);
            if (lastIntent.equalsIgnoreCase("TRANSLATION")) {
                bonus += 0.04;
            }
        }

        return rawScore + bonus;
    }

    /** 判断最近意图是否处于代码上下文 */
    private boolean isCodeContext(List<String> lastIntents) {
        long codeCount = lastIntents.stream()
                .filter(i -> i.equalsIgnoreCase("CODE_GENERATION")
                          || i.equalsIgnoreCase("REASONING"))
                .count();
        // 最近 3 轮中有代码相关意图
        int lookBack = Math.min(3, lastIntents.size());
        return lastIntents.subList(0, lookBack).stream()
                .anyMatch(i -> i.equalsIgnoreCase("CODE_GENERATION"));
    }

    /** 判断是否为"是什么/定义类"问题 */
    private boolean isDefinitionPattern(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        return lower.contains("是什么") || lower.contains("什么意思")
                || lower.contains("定义") || lower.contains("什么叫")
                || lower.contains("什么是") || lower.startsWith("怎么理解");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部：基础设施
    // ═══════════════════════════════════════════════════════════════════

    private boolean initCollection() {
        if (collectionReady) return true;
        synchronized (this) {
            if (collectionReady) return true;
            try {
                ensureCollection();
                milvusClient.loadCollection(
                        LoadCollectionParam.newBuilder().withCollectionName(COLLECTION_NAME).build());
                collectionReady = true;
                log.info("[ContextMatcher] collection {} 就绪 dim={}", COLLECTION_NAME, DIM);
            } catch (Exception e) {
                // 集合已存在可能抛异常，视为已就绪
                collectionReady = true;
                log.info("[ContextMatcher] collection {} 已经存在", COLLECTION_NAME);
            }
            return collectionReady;
        }
    }

    private void ensureCollection() {
        FieldType idField = FieldType.newBuilder()
                .withName("id").withDataType(DataType.Int64).withPrimaryKey(true).withAutoID(false).build();
        FieldType textField = FieldType.newBuilder()
                .withName("text").withDataType(DataType.VarChar).withMaxLength(1024).build();
        FieldType intentField = FieldType.newBuilder()
                .withName("intent").withDataType(DataType.VarChar).withMaxLength(64).build();
        FieldType vecField = FieldType.newBuilder()
                .withName("embedding").withDataType(DataType.FloatVector).withDimension(DIM).build();

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withShardsNum(1)
                .addFieldType(idField).addFieldType(textField).addFieldType(intentField).addFieldType(vecField)
                .build();

        milvusClient.createCollection(param);

        // 创建索引
        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .build());
    }

    private IntentCategory safeParseCategory(String name) {
        try {
            return IntentCategory.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IntentCategory.UNKNOWN;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════════════

    /** 投票器 */
    private static class Vote {
        int count;
        float maxScore;
        String topExample;
        void add(float score, String example) {
            count++;
            if (score > maxScore) {
                maxScore = score;
                topExample = example;
            }
        }
    }

    /** 滑动窗口命中率追踪 */
    private static class SlidingHitWindow {
        private final int maxSize;
        private final java.util.Deque<Boolean> queue = new java.util.concurrent.ConcurrentLinkedDeque<>();
        private final AtomicLong hitCount = new AtomicLong();
        private final AtomicLong totalCount = new AtomicLong();

        SlidingHitWindow(int maxSize) {
            this.maxSize = maxSize;
        }

        void add(boolean hit) {
            queue.addLast(hit);
            if (hit) hitCount.incrementAndGet();
            totalCount.incrementAndGet();

            // 溢出时逐出最早元素
            while (queue.size() > maxSize) {
                Boolean removed = queue.pollFirst();
                if (removed != null && removed) {
                    hitCount.decrementAndGet();
                }
                totalCount.decrementAndGet();
            }
        }

        double hitRate() {
            long total = totalCount.get();
            return total > 0 ? (double) hitCount.get() / total : 0;
        }

        long total() {
            return totalCount.get();
        }
    }

    /** intent-seeds.json 的数据结构 */
    public static class SeedEntry {
        private String text;
        private String intent;

        public SeedEntry() {}

        public SeedEntry(String text, String intent) {
            this.text = text;
            this.intent = intent;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getIntent() { return intent; }
        public void setIntent(String intent) { this.intent = intent; }
    }
}
