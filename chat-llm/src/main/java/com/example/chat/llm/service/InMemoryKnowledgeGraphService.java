package com.example.chat.llm.service;

import com.example.chat.storage.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>纯内存知识图谱服务（standalone 零基础设施依赖）</h2>
 *
 * <p>替代 Neo4j 版 {@link KnowledgeGraphService}（<code>app.knowledge-graph.backend=memory</code>）：
 * 用 <code>ConcurrentHashMap</code> 保存实体与关系，三元组抽取仍调用 LLM
 * （{@link TripleExtractionService}，与 Neo4j 版一致），查询/搜索/统计与
 * Neo4j 版返回完全相同的 <code>{nodes, edges}</code> / <code>{entityCount, relationCount}</code>
 * 结构，上层接口无需感知后端。</p>
 *
 * <p>数据仅存活于进程内、重启即失；批量导入（依赖 MySQL 历史数据）返回 false。</p>
 */
@Service
@ConditionalOnProperty(name = "app.knowledge-graph.backend", havingValue = "memory")
public class InMemoryKnowledgeGraphService implements KnowledgeGraphFacade, GraphStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryKnowledgeGraphService.class);

    /** 实体名 → 实体 */
    private final Map<String, Entity> entities = new ConcurrentHashMap<>();
    /** "sub|rel|obj" → 关系 */
    private final Map<String, Relation> relations = new ConcurrentHashMap<>();
    /** 抽取去重 key（source:messageId） */
    private final Set<String> extractedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong idSeq = new AtomicLong(1);

    private final TripleExtractionService tripleExtractionService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kg-memory-extractor");
        t.setDaemon(true);
        return t;
    });

    public InMemoryKnowledgeGraphService(TripleExtractionService tripleExtractionService) {
        this.tripleExtractionService = tripleExtractionService;
        log.info("[MemoryKnowledgeGraph] 纯内存知识图谱已启用（进程内存储，重启即失）");
    }

    // ── KnowledgeGraphFacade ─────────────────────────────────────

    @Override
    public Map<String, Object> getGraph(int limit, int minEntityWeight, int minRelationWeight) {
        List<Entity> top = entities.values().stream()
                .filter(e -> e.relCount >= minEntityWeight)
                .sorted(Comparator.comparingInt((Entity e) -> e.relCount).reversed())
                .limit(Math.max(1, limit))
                .toList();
        if (top.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        Set<Long> ids = new HashSet<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Entity e : top) {
            ids.add(e.id);
            nodes.add(Map.of("id", e.id, "label", e.name, "value", e.relCount));
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Relation r : relations.values()) {
            Entity s = entities.get(r.subject);
            Entity o = entities.get(r.object);
            if (s == null || o == null || !ids.contains(s.id) || !ids.contains(o.id)) {
                continue;
            }
            if (r.count >= minRelationWeight) {
                edges.add(edgeView(s.id, o.id, r));
            }
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    @Override
    @SuppressWarnings("PMD.NPathComplexity") // 图谱搜索：关键词过滤/一跳邻居扩展/关系筛选，拆分破坏单次遍历
    public Map<String, Object> searchEntities(String keyword, int limit, int minEntityWeight, int minRelationWeight) {
        if (keyword == null || keyword.isBlank()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        String kw = keyword.toLowerCase(Locale.ROOT);
        List<Entity> matched = entities.values().stream()
                .filter(e -> e.name.toLowerCase(Locale.ROOT).contains(kw) && e.relCount >= minEntityWeight)
                .sorted(Comparator.comparingInt((Entity e) -> e.relCount).reversed())
                .limit(Math.max(1, limit))
                .toList();
        if (matched.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        Map<Long, Entity> byId = new HashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Entity e : matched) {
            byId.put(e.id, e);
            nodes.add(Map.of("id", e.id, "label", e.name, "value", e.relCount));
        }
        // 一跳邻居
        for (Entity e : matched) {
            for (Relation r : relations.values()) {
                if (e.name.equals(r.subject) || e.name.equals(r.object)) {
                    Entity neighbor = e.name.equals(r.subject)
                            ? entities.get(r.object) : entities.get(r.subject);
                    if (neighbor != null && !byId.containsKey(neighbor.id)) {
                        byId.put(neighbor.id, neighbor);
                        nodes.add(Map.of("id", neighbor.id, "label", neighbor.name,
                                "value", neighbor.relCount));
                    }
                }
            }
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Relation r : relations.values()) {
            Entity s = entities.get(r.subject);
            Entity o = entities.get(r.object);
            if (s == null || o == null || !byId.containsKey(s.id) || !byId.containsKey(o.id)) {
                continue;
            }
            if (r.count >= minRelationWeight) {
                edges.add(edgeView(s.id, o.id, r));
            }
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    @Override
    public Map<String, Object> getStats() {
        return Map.of("entityCount", (long) entities.size(),
                "relationCount", (long) relations.size());
    }

    @Override
    public void extractAndSaveAsync(Long messageId, String question, String answer, String source) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        String key = (source != null ? source : "chat") + ":" + (messageId != null ? messageId : question.hashCode());
        if (!extractedKeys.add(key)) {
            log.debug("[MemoryKnowledgeGraph] 消息已抽取过，跳过: {}", key);
            return;
        }
        executor.submit(() -> {
            try {
                List<Map<String, String>> triples = tripleExtractionService.extractTriples(question, answer);
                if (triples.isEmpty()) {
                    return;
                }
                saveTriples(source != null ? source : "chat", triples);
                log.info("[MemoryKnowledgeGraph] 抽取 {} 个三元组 from msg={} source={}",
                        triples.size(), messageId, source);
            } catch (Exception e) {
                log.warn("[MemoryKnowledgeGraph] 抽取失败 msg={}: {}", messageId, e.getMessage());
            }
        });
    }

    @Override
    public boolean startBatchImport() {
        log.info("[MemoryKnowledgeGraph] standalone 无 MySQL 历史数据可导入，跳过批量导入");
        return false;
    }

    @Override
    public boolean isImporting() {
        return false;
    }

    // ── GraphStore SPI（存储平台化） ─────────────────────────────

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public Map<String, Object> query(String cypher) {
        // 内存版不支持 Cypher，返回空结果（调用方安全降级）
        log.debug("[MemoryKnowledgeGraph] 不支持 Cypher 查询: {}", cypher);
        return new LinkedHashMap<>();
    }

    @Override
    public int saveTriples(String source, List<Map<String, String>> triples) {
        if (triples == null || triples.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (Map<String, String> t : triples) {
            String subject = t.get("subject");
            String relation = t.get("relation");
            String object = t.get("object");
            if (subject == null || subject.isBlank() || relation == null || relation.isBlank()
                    || object == null || object.isBlank()) {
                continue;
            }
            Entity s = entities.computeIfAbsent(subject, n -> new Entity(idSeq.getAndIncrement(), n));
            Entity o = entities.computeIfAbsent(object, n -> new Entity(idSeq.getAndIncrement(), n));
            s.relCount++;
            o.relCount++;
            relations.compute(key(subject, relation, object), (k, r) -> {
                if (r == null) {
                    return new Relation(subject, relation, object, 1, source);
                }
                r.count++;
                return r;
            });
            saved++;
        }
        return saved;
    }

    @Override
    public String name() {
        return "memory";
    }

    // ── 内部结构 ─────────────────────────────────────────────────

    private static String key(String subject, String relation, String object) {
        return subject + "|" + relation + "|" + object;
    }

    private static Map<String, Object> edgeView(long source, long target, Relation r) {
        Map<String, Object> edge = new HashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        edge.put("label", r.relType);
        edge.put("weight", r.count);
        if (r.question != null) {
            edge.put("question", r.question);
        }
        return edge;
    }

    private static final class Entity {
        final long id;
        final String name;
        int relCount;

        Entity(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Relation {
        final String subject;
        final String relType;
        final String object;
        int count;
        String source;
        String question;

        Relation(String subject, String relType, String object, int count, String source) {
            this.subject = subject;
            this.relType = relType;
            this.object = object;
            this.count = count;
            this.source = source;
        }
    }
}
