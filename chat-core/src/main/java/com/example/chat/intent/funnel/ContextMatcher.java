package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentResult;
import com.example.chat.intent.IntentCategory;
import com.example.chat.rag.service.EmbeddingService;
import com.example.chat.rag.service.VectorStoreService;
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
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 意图漏斗 — 第二层：上下文语义匹配。
 *
 * <pre>
 *   用 embedding + Milvus 做语义级意图识别。
 *   维护 intent_examples 向量集合，每轮对话 embed → k-NN → 投票出最高意图。
 *
 *   速度：~30-80ms（embedding + Milvus 检索）
 *   命中率目标：70-85%（接住绝大多数常规意图，不经过 LLM）
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

    /** 相似度阈值（低于此分值的舍弃） */
    private volatile double matchThreshold = 0.75;

    /** 检索 topK */
    private volatile int topK = 5;

    /** collection 是否已就绪（懒初始化） */
    private volatile boolean collectionReady;

    // ────── 意图类别置信度权重映射（某些类别更容易被语义匹配，降低阈值） ──────
    private static final Map<String, Double> CATEGORY_THRESHOLD_OVERRIDE = new ConcurrentHashMap<>();
    static {
        CATEGORY_THRESHOLD_OVERRIDE.put("GENERAL_CHAT", 0.72);
        CATEGORY_THRESHOLD_OVERRIDE.put("EMOTIONAL_SUPPORT", 0.70);
        CATEGORY_THRESHOLD_OVERRIDE.put("TRANSLATION", 0.80);
        CATEGORY_THRESHOLD_OVERRIDE.put("CODE_GENERATION", 0.80);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对外接口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 上下文语义匹配。
     *
     * @param text      用户输入
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

        try {
            // 1) embed
            float[] vec = embeddingService.embed(text);
            List<Float> queryVec = new ArrayList<>(vec.length);
            for (float v : vec) queryVec.add(v);

            // 2) k-NN 检索
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topK)
                    .withVectors(List.of(queryVec))
                    .withVectorFieldName("embedding")
                    .withOutFields(List.of("text", "intent"))
                    .withParams("{\"nprobe\":16}")
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

            if (votes.isEmpty()) return Optional.empty();

            // 取最高分
            Map.Entry<String, Vote> best = votes.entrySet().stream()
                    .max((a, b) -> Double.compare(a.getValue().maxScore, b.getValue().maxScore))
                    .orElse(null);
            if (best == null) return Optional.empty();

            double bestScore = best.getValue().maxScore;
            double threshold = CATEGORY_THRESHOLD_OVERRIDE.getOrDefault(best.getKey(), matchThreshold);

            // 4) 上下文消歧：如果上轮意图与本轮匹配意图不同且分数接近阈值，降权
            double adjustedScore = adjustScoreWithContext(best.getKey(), bestScore, lastIntents);

            if (adjustedScore < threshold) {
                log.debug("[ContextMatcher] top-intent={} score={:.3f} < threshold={:.3f} → no match",
                         best.getKey(), adjustedScore, threshold);
                return Optional.empty();
            }

            // 5) 构建 IntentResult
            IntentCategory cat = safeParseCategory(best.getKey());
            IntentResult result = new IntentResult(
                    cat,
                    (float) Math.min(adjustedScore, 1.0),
                    "context-match: top=" + best.getKey() + " score=" + String.format("%.3f", adjustedScore)
                    + " examples=" + best.getValue().count,
                    best.getValue().topExample
            );

            log.info("[ContextMatcher] 命中: intent={} score={:.3f} kNN-count={}",
                     best.getKey(), adjustedScore, best.getValue().count);
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("[ContextMatcher] 语义匹配异常: {}", e.getMessage());
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
    //  内部
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

    /** 用历史意图做上下文消歧 */
    private double adjustScoreWithContext(String matchedIntent, double rawScore, List<String> lastIntents) {
        if (lastIntents == null || lastIntents.isEmpty()) return rawScore;
        // 如果最近 1-2 轮意图与本轮匹配一致 → 轻微加分
        long recentSameCount = lastIntents.stream()
                .filter(i -> i.equalsIgnoreCase(matchedIntent))
                .count();
        if (recentSameCount >= 2) return rawScore + 0.05;
        if (recentSameCount == 1) return rawScore + 0.02;
        return rawScore;
    }

    private IntentCategory safeParseCategory(String name) {
        try {
            return IntentCategory.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IntentCategory.UNKNOWN;
        }
    }

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
}
