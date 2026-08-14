package com.example.chat.llm.rag.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>纯内存用户事实记忆（L2，standalone 零基础设施依赖）</h2>
 *
 * <p>替代 Milvus 版 {@link UserFactMemoryService}（<code>app.rag.backend=memory</code>）：
 * 每用户维护 <code>Map&lt;事实文本, MemFact&gt;</code>（含稠密向量），
 * 抽取复用 {@link FactExtractor}，召回用余弦相似度按阈值过滤。</p>
 *
 * <p>数据仅存活于进程内、重启即失。</p>
 */
@Service
@ConditionalOnExpression("'${app.rag.enabled:false}' == 'true' and '${app.rag.backend:milvus}' == 'memory'")
public class InMemoryUserFactMemoryService implements UserFactMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserFactMemoryService.class);

    /** userId → 事实库（key 为事实文本，天然去重） */
    private final Map<Long, Map<String, MemFact>> factsByUser = new ConcurrentHashMap<>();

    private final FactExtractor factExtractor;
    private final LegacyEmbeddingService embeddingService;

    @Value("${app.rag.memory.fact-top-k:5}")
    private int recallTopK;

    @Value("${app.rag.memory.fact-threshold:0.35}")
    private float recallThreshold;

    public InMemoryUserFactMemoryService(FactExtractor factExtractor,
                                         LegacyEmbeddingService embeddingService) {
        this.factExtractor = factExtractor;
        this.embeddingService = embeddingService;
        log.info("[MemoryFactMemory] 纯内存用户事实记忆已启用（进程内存储，重启即失）");
    }

    @Override
    public void saveFacts(String scene, Long userId, String question, String answer) {
        if (userId == null || question == null || question.isBlank()) {
            return;
        }
        try {
            List<String> facts = factExtractor.extractFacts(question, answer);
            if (facts.isEmpty()) {
                log.debug("[MemoryFactMemory] 本轮无新事实 scene={} user={}", scene, userId);
                return;
            }
            Map<String, MemFact> bucket = factsByUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
            int saved = 0;
            for (String fact : facts) {
                if (bucket.containsKey(fact)) {
                    continue;
                }
                try {
                    float[] vec = embeddingService.embed(fact);
                    if (vec.length == 0) {
                        continue;
                    }
                    bucket.put(fact, new MemFact(fact, vec, scene != null ? scene : "personal",
                            System.currentTimeMillis()));
                    saved++;
                } catch (Exception e) {
                    log.warn("[MemoryFactMemory] 事实向量化失败 user={} fact={} error={}",
                            userId, fact, e.getMessage());
                }
            }
            log.info("[MemoryFactMemory] 保存完成 scene={} user={} 抽取={} 新增={}",
                    scene, userId, facts.size(), saved);
        } catch (Exception e) {
            log.warn("[MemoryFactMemory] 保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    @Override
    public List<String> recallFacts(Long userId, String question, int topK) {
        if (userId == null || question == null || question.isBlank()) {
            return List.of();
        }
        Map<String, MemFact> bucket = factsByUser.get(userId);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        try {
            float[] queryVec = embeddingService.embed(question);
            if (queryVec.length == 0) {
                return List.of();
            }
            List<ScoredFact> scored = new ArrayList<>();
            for (MemFact f : bucket.values()) {
                float cos = cosine(queryVec, f.vector);
                if (!Float.isNaN(cos) && cos >= recallThreshold) {
                    scored.add(new ScoredFact(f.text, cos));
                }
            }
            scored.sort(Comparator.comparingDouble((ScoredFact s) -> s.score).reversed());
            int n = Math.min(topK > 0 ? topK : recallTopK, scored.size());
            List<String> facts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                facts.add(scored.get(i).text);
            }
            return facts;
        } catch (Exception e) {
            log.warn("[MemoryFactMemory] 召回失败 user={} error={}", userId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("PMD.UseVarargs") // 双数组参数，varargs 只能修饰最后一个参数，无法转换
    private float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return Float.NaN;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return Float.NaN;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private static final class MemFact {
        final String text;
        final float[] vector;
        final String scene;
        final long ts;

        MemFact(String text, float[] vector, String scene, long ts) {
            this.text = text;
            this.vector = vector.clone();   // 防御性拷贝，避免外部数组被直接存储
            this.scene = scene;
            this.ts = ts;
        }
    }

    private record ScoredFact(String text, float score) {}
}
