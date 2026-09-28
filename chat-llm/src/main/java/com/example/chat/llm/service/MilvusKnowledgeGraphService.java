package com.example.chat.llm.service;

import com.example.chat.llm.rag.legacy.LegacyEmbeddingService;
import com.example.chat.storage.GraphStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <h2>Milvus 知识图谱后端（<code>app.knowledge-graph.backend=milvus</code>）</h2>
 *
 * <p>知识图谱的<b>写入与查询全部走 Milvus</b>，不依赖 Neo4j。复用 RAG 的
 * {@code milvusServiceClient}（{@code app.rag.enabled=true} 时由
 * {@code LegacyRagConfig} 提供）与 {@link LegacyEmbeddingService} 向量化：</p>
 *
 * <p>Collection 设计（PK 为内容指纹，天然幂等，upsert 去重）：</p>
 * <ul>
 *   <li><b>kg_entity</b>：id(PK=sha256(name)) / name / rel_count / embedding(实体名向量，
 *       HNSW+COSINE，支持 searchEntities 语义召回)</li>
 *   <li><b>kg_triple</b>：id(PK=sha256(s|r|o)) / subject / relation / object / count /
 *       source / question / embedding(复用 subject 向量，省一次向量化调用)</li>
 * </ul>
 *
 * <p>图谱可视化（getGraph）/ 邻居扩展用标量 expr 查询，实体搜索优先向量召回、
 * embedding 服务不可用时回退 {@code like} 关键词。与 Neo4j / memory 版返回
 * 完全相同的 <code>{nodes, edges}</code> 结构，上层接口无感知。</p>
 *
 * <p>限制：不支持 Cypher（GraphStore.query 返回空）；批量导入历史数据返回 false
 * （BatchImportService 绑定 Neo4j Driver）。</p>
 */
@Service
@ConditionalOnExpression("'${app.knowledge-graph.enabled:false}' == 'true' and '${app.knowledge-graph.backend:neo4j}' == 'milvus'")
public class MilvusKnowledgeGraphService implements KnowledgeGraphFacade, GraphStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusKnowledgeGraphService.class);

    public static final String ENTITY_COLLECTION = "kg_entity";
    public static final String TRIPLE_COLLECTION = "kg_triple";

    /** 标量查询的硬上限（Milvus query 单次默认上限 16384，取保守值） */
    private static final long SCAN_LIMIT = 4096;

    private final MilvusServiceClient milvusClient;
    private final LegacyEmbeddingService embeddingService;
    private final TripleExtractionService tripleExtractionService;
    private StringRedisTemplate redisTemplate;

    @Value("${app.rag.milvus.dimension:1024}")
    private int dimension;

    @Value("${app.knowledge-graph.milvus.search-threshold:0.35}")
    private float searchThreshold;

    /** 抽取去重 key（source:messageId），Redis 不可用时的进程内兜底 */
    private final Set<String> extractedKeys = ConcurrentHashMap.newKeySet();

    /** 实体向量缓存（upsert 需回传全字段，避免同名实体反复向量化） */
    private final Map<String, float[]> embedCache = new ConcurrentHashMap<>();

    private volatile boolean ready;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kg-milvus-extractor");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public MilvusKnowledgeGraphService(
            @Qualifier("milvusServiceClient") @Autowired(required = false) MilvusServiceClient milvusClient,
            @Autowired(required = false) LegacyEmbeddingService embeddingService,
            TripleExtractionService tripleExtractionService) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.tripleExtractionService = tripleExtractionService;
        if (milvusClient == null) {
            log.warn("[MilvusKG] Milvus 客户端不可用（需 app.rag.enabled=true 且 app.rag.backend=milvus），"
                    + "知识图谱将只登记不落库");
        } else {
            log.info("[MilvusKG] Milvus 知识图谱后端已启用 dim={} embedding={}",
                    dimension, embeddingService != null);
        }
    }

    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
    }

    // ═════════════════════════ KnowledgeGraphFacade ═════════════════════════

    @Override
    public Map<String, Object> getGraph(int limit, int minEntityWeight, int minRelationWeight) {
        if (!ensureCollections()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        try {
            List<EntityRow> top = topEntities(null, minEntityWeight, Math.max(1, limit));
            if (top.isEmpty()) {
                return Map.of("nodes", List.of(), "edges", List.of());
            }
            Map<String, Long> idByName = new HashMap<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (EntityRow e : top) {
                idByName.put(e.name, e.id);
                nodes.add(Map.of("id", e.id, "label", e.name, "value", e.relCount));
            }
            List<Map<String, Object>> edges = edgesAmong(idByName, minRelationWeight);
            return Map.of("nodes", nodes, "edges", edges);
        } catch (Exception e) {
            log.warn("[MilvusKG] getGraph 失败: {}", e.getMessage());
            return Map.of("nodes", List.of(), "edges", List.of());
        }
    }

    @Override
    @SuppressWarnings("PMD.NPathComplexity") // 召回/一跳扩展/回退分支，拆分破坏一次查询流水线
    public Map<String, Object> searchEntities(String keyword, int limit, int minEntityWeight, int minRelationWeight) {
        if (keyword == null || keyword.isBlank() || !ensureCollections()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        try {
            // ① 关键词命中实体：优先向量语义召回，embedding 不可用回退 like
            List<EntityRow> matched = semanticSearch(keyword, minEntityWeight, Math.max(1, limit));
            if (matched.isEmpty()) {
                return Map.of("nodes", List.of(), "edges", List.of());
            }
            Map<String, Long> idByName = new LinkedHashMap<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (EntityRow e : matched) {
                idByName.put(e.name, e.id);
                nodes.add(Map.of("id", e.id, "label", e.name, "value", e.relCount));
            }
            // ② 一跳邻居：取涉及命中实体的全部三元组，拉取对端实体
            List<TripleRow> around = queryTriples(exprNamesIn(idByName.keySet()), SCAN_LIMIT);
            Map<String, Integer> neighborNames = new LinkedHashMap<>();
            for (TripleRow t : around) {
                if (idByName.containsKey(t.subject) && !idByName.containsKey(t.object)) {
                    neighborNames.put(t.object, 0);
                }
                if (idByName.containsKey(t.object) && !idByName.containsKey(t.subject)) {
                    neighborNames.put(t.subject, 0);
                }
            }
            if (!neighborNames.isEmpty()) {
                for (EntityRow n : fetchEntities(neighborNames.keySet(), minEntityWeight)) {
                    if (!idByName.containsKey(n.name)) {
                        idByName.put(n.name, n.id);
                        nodes.add(Map.of("id", n.id, "label", n.name, "value", n.relCount));
                    }
                }
            }
            // ③ 边：命中集合内部且权重达标
            List<Map<String, Object>> edges = edgesAmong(idByName, minRelationWeight);
            return Map.of("nodes", nodes, "edges", edges);
        } catch (Exception e) {
            log.warn("[MilvusKG] searchEntities 失败: {}", e.getMessage());
            return Map.of("nodes", List.of(), "edges", List.of());
        }
    }

    @Override
    public Map<String, Object> getStats() {
        if (!ensureCollections()) {
            return Map.of("entityCount", 0L, "relationCount", 0L);
        }
        try {
            return Map.of("entityCount", rowCount(ENTITY_COLLECTION),
                    "relationCount", rowCount(TRIPLE_COLLECTION));
        } catch (Exception e) {
            log.warn("[MilvusKG] getStats 失败: {}", e.getMessage());
            return Map.of("entityCount", 0L, "relationCount", 0L);
        }
    }

    @Override
    public void extractAndSaveAsync(Long messageId, String question, String answer, String source) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        String key = (source != null ? source : "chat") + ":" + (messageId != null ? messageId : question.hashCode());
        if (!markExtracting(key)) {
            log.debug("[MilvusKG] 消息已抽取过，跳过: {}", key);
            return;
        }
        executor.submit(() -> {
            try {
                List<Map<String, String>> triples = tripleExtractionService.extractTriples(question, answer);
                if (triples.isEmpty()) {
                    return;
                }
                int saved = saveTriples(source != null ? source : "chat", triples);
                log.info("[MilvusKG] 抽取 {} 个三元组 from msg={} source={} 落库 {}", 
                        triples.size(), messageId, source, saved);
            } catch (Exception e) {
                log.warn("[MilvusKG] 抽取失败 msg={}: {}", messageId, e.getMessage());
            }
        });
    }

    @Override
    public boolean startBatchImport() {
        log.info("[MilvusKG] 历史批量导入依赖 Neo4j Driver，Milvus 后端不支持，跳过");
        return false;
    }

    @Override
    public boolean isImporting() {
        return false;
    }

    // ═════════════════════════ GraphStore SPI ═════════════════════════

    @Override
    public boolean isConnected() {
        return milvusClient != null && (ready || ensureCollections());
    }

    @Override
    public Map<String, Object> query(String cypher) {
        // Milvus 不支持 Cypher，返回空结果（调用方安全降级）
        log.debug("[MilvusKG] 不支持 Cypher 查询: {}", cypher);
        return new LinkedHashMap<>();
    }

    @Override
    public int saveTriples(String source, List<Map<String, String>> triples) {
        if (milvusClient == null || triples == null || triples.isEmpty() || !ensureCollections()) {
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
            try {
                upsertEntity(subject.trim());
                upsertEntity(object.trim());
                upsertTriple(subject.trim(), relation.trim(), object.trim(), source,
                        t.get("question"));
                saved++;
            } catch (Exception e) {
                log.warn("[MilvusKG] 三元组写入失败 {}|{}|{}: {}", subject, relation, object, e.getMessage());
            }
        }
        return saved;
    }

    @Override
    public String name() {
        return "milvus";
    }

    // ═════════════════════════ Collection 初始化 ═════════════════════════

    /** 幂等建集合 + 索引 + 加载；失败重置标记，下次操作重试 */
    private boolean ensureCollections() {
        if (ready) {
            return true;
        }
        if (milvusClient == null) {
            return false;
        }
        try {
            createEntityCollectionIfAbsent();
            createTripleCollectionIfAbsent();
            ready = true;
            log.info("[MilvusKG] Collection {}/{} 就绪 dim={}", ENTITY_COLLECTION, TRIPLE_COLLECTION, dimension);
            return true;
        } catch (Exception e) {
            ready = false;
            log.warn("[MilvusKG] Collection 初始化失败（Milvus 未就绪？）: {}", e.getMessage());
            return false;
        }
    }

    private void createEntityCollectionIfAbsent() {
        if (hasCollection(ENTITY_COLLECTION)) {
            return;
        }
        milvusClient.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(ENTITY_COLLECTION)
                .withDescription("知识图谱-实体（backend=milvus）")
                .withShardsNum(1)
                .addFieldType(FieldType.newBuilder().withName("id")
                        .withDataType(DataType.Int64).withPrimaryKey(true).build())
                .addFieldType(FieldType.newBuilder().withName("name")
                        .withDataType(DataType.VarChar).withMaxLength(512).build())
                .addFieldType(FieldType.newBuilder().withName("rel_count")
                        .withDataType(DataType.Int64).build())
                .addFieldType(FieldType.newBuilder().withName("embedding")
                        .withDataType(DataType.FloatVector).withDimension(dimension).build())
                .build());
        createHnswIndex(ENTITY_COLLECTION);
        load(ENTITY_COLLECTION);
        log.info("[MilvusKG] {} 创建完成 dim={}", ENTITY_COLLECTION, dimension);
    }

    private void createTripleCollectionIfAbsent() {
        if (hasCollection(TRIPLE_COLLECTION)) {
            return;
        }
        milvusClient.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(TRIPLE_COLLECTION)
                .withDescription("知识图谱-三元组（backend=milvus）")
                .withShardsNum(1)
                .addFieldType(FieldType.newBuilder().withName("id")
                        .withDataType(DataType.Int64).withPrimaryKey(true).build())
                .addFieldType(FieldType.newBuilder().withName("subject")
                        .withDataType(DataType.VarChar).withMaxLength(512).build())
                .addFieldType(FieldType.newBuilder().withName("relation")
                        .withDataType(DataType.VarChar).withMaxLength(255).build())
                .addFieldType(FieldType.newBuilder().withName("object")
                        .withDataType(DataType.VarChar).withMaxLength(512).build())
                .addFieldType(FieldType.newBuilder().withName("count")
                        .withDataType(DataType.Int64).build())
                .addFieldType(FieldType.newBuilder().withName("source")
                        .withDataType(DataType.VarChar).withMaxLength(255).build())
                .addFieldType(FieldType.newBuilder().withName("question")
                        .withDataType(DataType.VarChar).withMaxLength(1024).build())
                .addFieldType(FieldType.newBuilder().withName("embedding")
                        .withDataType(DataType.FloatVector).withDimension(dimension).build())
                .build());
        createHnswIndex(TRIPLE_COLLECTION);
        load(TRIPLE_COLLECTION);
        log.info("[MilvusKG] {} 创建完成", TRIPLE_COLLECTION);
    }

    private boolean hasCollection(String name) {
        return milvusClient.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(name).build()).getData();
    }

    private void createHnswIndex(String collection) {
        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName("embedding")
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                .build());
    }

    private void load(String collection) {
        milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collection).build());
    }

    // ═════════════════════════ 写入（upsert 幂等 + 计数自增） ═════════════════════════

    private void upsertEntity(String name) {
        long id = fingerprint(name);
        long newCount = currentEntityCount(name) + 1;
        List<Float> vector = vectorOf(name);
        milvusClient.upsert(UpsertParam.newBuilder()
                .withCollectionName(ENTITY_COLLECTION)
                .withFields(entityFields(id, name, newCount, vector))
                .build());
    }

    private void upsertTriple(String subject, String relation, String object,
                              String source, String question) {
        long id = fingerprint(subject + "|" + relation + "|" + object);
        long newCount = currentTripleCount(id) + 1;
        // 复用 subject 向量：避免每条三元组多一次向量化调用
        List<Float> vector = vectorOf(subject);
        milvusClient.upsert(UpsertParam.newBuilder()
                .withCollectionName(TRIPLE_COLLECTION)
                .withFields(List.of(
                        new InsertParam.Field("id", List.of(id)),
                        new InsertParam.Field("subject", List.of(subject)),
                        new InsertParam.Field("relation", List.of(relation)),
                        new InsertParam.Field("object", List.of(object)),
                        new InsertParam.Field("count", List.of(newCount)),
                        new InsertParam.Field("source", List.of(source != null ? source : "chat")),
                        new InsertParam.Field("question",
                                List.of(question != null ? question : "")),
                        new InsertParam.Field("embedding", List.of(vector))))
                .build());
    }

    private List<InsertParam.Field> entityFields(long id, String name, long relCount, List<Float> vector) {
        return List.of(
                new InsertParam.Field("id", List.of(id)),
                new InsertParam.Field("name", List.of(name)),
                new InsertParam.Field("rel_count", List.of(relCount)),
                new InsertParam.Field("embedding", List.of(vector)));
    }

    /** 实体当前连接数（不存在则 0） */
    private long currentEntityCount(String name) {
        List<EntityRow> rows = fetchEntities(Set.of(name), 0);
        return rows.isEmpty() ? 0 : rows.get(0).relCount;
    }

    /** 三元组当前出现次数（不存在则 0） */
    private long currentTripleCount(long id) {
        R<io.milvus.grpc.QueryResults> resp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(TRIPLE_COLLECTION)
                .withExpr("id == " + id)
                .withOutFields(List.of("count"))
                .withLimit(1L)
                .build());
        if (resp.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
            return 0;
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(resp.getData());
        List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();
        if (records.isEmpty()) {
            return 0;
        }
        Object v = records.get(0).get("count");
        return v instanceof Number n ? n.longValue() : 0;
    }

    // ═════════════════════════ 查询 ═════════════════════════

    /** top 实体：nameLike 为空时全量扫描取 topN，否则 like 过滤 */
    private List<EntityRow> topEntities(String nameLike, int minWeight, int limit) {
        String expr = nameLike == null
                ? "rel_count >= " + Math.max(0, minWeight)
                : "name like \"%"
                        + nameLike.replace("\\", "\\\\").replace("\"", "").replace("%", "")
                        + "%\" and rel_count >= " + Math.max(0, minWeight);
        R<io.milvus.grpc.QueryResults> resp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(ENTITY_COLLECTION)
                .withExpr(expr)
                .withOutFields(List.of("name", "rel_count"))
                .withLimit(SCAN_LIMIT)
                .build());
        if (resp.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
            log.warn("[MilvusKG] 实体查询失败 status={}", resp.getStatus());
            return List.of();
        }
        List<EntityRow> rows = toEntityRows(new QueryResultsWrapper(resp.getData()));
        rows.sort(Comparator.comparingLong((EntityRow r) -> r.relCount).reversed());
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    /** 按名称精确拉取实体（weight 过滤） */
    private List<EntityRow> fetchEntities(Set<String> names, int minWeight) {
        if (names.isEmpty()) {
            return List.of();
        }
        String expr = "name in [" + names.stream()
                .map(n -> "\"" + escape(n) + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("\"\"") + "]"
                + (minWeight > 0 ? " and rel_count >= " + minWeight : "");
        R<io.milvus.grpc.QueryResults> resp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(ENTITY_COLLECTION)
                .withExpr(expr)
                .withOutFields(List.of("name", "rel_count"))
                .withLimit(SCAN_LIMIT)
                .build());
        if (resp.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
            return List.of();
        }
        return toEntityRows(new QueryResultsWrapper(resp.getData()));
    }

    /** 语义召回：embedding 可用走向量搜索，否则回退 like */
    private List<EntityRow> semanticSearch(String keyword, int minWeight, int limit) {
        if (embeddingService != null) {
            try {
                float[] vec = embeddingService.embed(keyword);
                if (vec.length > 0) {
                    List<Float> queryVec = new ArrayList<>(vec.length);
                    for (float v : vec) {
                        queryVec.add(v);
                    }
                    SearchResults resp = milvusClient.search(SearchParam.newBuilder()
                            .withCollectionName(ENTITY_COLLECTION)
                            .withMetricType(MetricType.COSINE)
                            .withTopK(limit)
                            .withVectors(List.of(queryVec))
                            .withVectorFieldName("embedding")
                            .withExpr("rel_count >= " + Math.max(0, minWeight))
                            .withOutFields(List.of("name", "rel_count"))
                            .withParams("{\"ef\":64}")
                            .build()).getData();
                    SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getResults());
                    List<EntityRow> out = new ArrayList<>();
                    int rowCount = wrapper.getIDScore(0).size();
                    for (int i = 0; i < rowCount; i++) {
                        if (wrapper.getIDScore(0).get(i).getScore() < searchThreshold) {
                            continue;
                        }
                        Object name = wrapper.getFieldData("name", 0).get(i);
                        Object count = wrapper.getFieldData("rel_count", 0).get(i);
                        if (name != null && !name.toString().isBlank()) {
                            long c = count instanceof Number n ? n.longValue() : 0L;
                            out.add(new EntityRow(fingerprint(name.toString()), name.toString(), c));
                        }
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            } catch (Exception e) {
                log.debug("[MilvusKG] 向量召回失败，回退 like: {}", e.getMessage());
            }
        }
        return topEntities(keyword, minWeight, limit);
    }

    /** 查询涉及给定名称集合的三元组 */
    private List<TripleRow> queryTriples(String namesInExpr, long limit) {
        R<io.milvus.grpc.QueryResults> resp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(TRIPLE_COLLECTION)
                .withExpr("(" + namesInExpr + ")")
                .withOutFields(List.of("subject", "relation", "object", "count", "question"))
                .withLimit(limit)
                .build());
        if (resp.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
            return List.of();
        }
        List<TripleRow> out = new ArrayList<>();
        for (QueryResultsWrapper.RowRecord r : new QueryResultsWrapper(resp.getData()).getRowRecords()) {
            Object q = r.get("question");
            out.add(new TripleRow(
                    str(r.get("subject")), str(r.get("relation")), str(r.get("object")),
                    r.get("count") instanceof Number n ? n.longValue() : 1L,
                    q != null && !q.toString().isBlank() ? q.toString() : null));
        }
        return out;
    }

    /** idByName 集合内部、权重达标的边 */
    private List<Map<String, Object>> edgesAmong(Map<String, Long> idByName, int minRelationWeight) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (TripleRow t : queryTriples(exprNamesIn(idByName.keySet()), SCAN_LIMIT)) {
            Long s = idByName.get(t.subject);
            Long o = idByName.get(t.object);
            if (s == null || o == null || t.count < minRelationWeight) {
                continue;
            }
            Map<String, Object> edge = new HashMap<>();
            edge.put("source", s);
            edge.put("target", o);
            edge.put("label", t.relation);
            edge.put("weight", t.count);
            if (t.question != null) {
                edge.put("question", t.question);
            }
            edges.add(edge);
        }
        return edges;
    }

    /** Collection 行数统计 */
    private long rowCount(String collection) {
        R<io.milvus.grpc.GetCollectionStatisticsResponse> resp =
                milvusClient.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder()
                        .withCollectionName(collection).build());
        if (resp.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
            return 0L;
        }
        for (io.milvus.grpc.KeyValuePair kv : resp.getData().getStatsList()) {
            if ("row_count".equalsIgnoreCase(kv.getKey()) && !kv.getValue().isBlank()) {
                return Long.parseLong(kv.getValue().trim());
            }
        }
        return 0L;
    }

    // ═════════════════════════ 工具 ═════════════════════════

    /** `name in ["a","b"]`（或 kg_triple：`subject in [...] or object in [...]`） */
    private String exprNamesIn(Set<String> names) {
        String quoted = names.stream()
                .map(n -> "\"" + escape(n) + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("\"\"");
        return "subject in [" + quoted + "] or object in [" + quoted + "]";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private List<EntityRow> toEntityRows(QueryResultsWrapper wrapper) {
        List<EntityRow> rows = new ArrayList<>();
        for (QueryResultsWrapper.RowRecord r : wrapper.getRowRecords()) {
            Object name = r.get("name");
            if (name == null || name.toString().isBlank()) {
                continue;
            }
            long count = r.get("rel_count") instanceof Number n ? n.longValue() : 0L;
            rows.add(new EntityRow(fingerprint(name.toString()), name.toString(), count));
        }
        return rows;
    }

    /** 实体名向量（带缓存；embedding 不可用回退零向量，保证图谱查询仍可用） */
    private List<Float> vectorOf(String name) {
        float[] cached = embedCache.computeIfAbsent(name, n -> {
            if (embeddingService == null) {
                return new float[0];
            }
            try {
                return embeddingService.embed(n);
            } catch (Exception e) {
                log.debug("[MilvusKG] 实体向量化失败 {}: {}", n, e.getMessage());
                return new float[0];
            }
        });
        if (embedCache.size() > 5000) {
            embedCache.clear();
        }
        if (cached.length != dimension) {
            float[] zeros = new float[dimension];
            List<Float> v = new ArrayList<>(dimension);
            for (float f : zeros) {
                v.add(f);
            }
            return v;
        }
        List<Float> v = new ArrayList<>(cached.length);
        for (float f : cached) {
            v.add(f);
        }
        return v;
    }

    /** 内容指纹 PK：sha256 前 8 字节（非负 Int64），同名/同三元组天然幂等 */
    static long fingerprint(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            long v = 0L;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (hash[i] & 0xFFL);
            }
            return v & Long.MAX_VALUE;
        } catch (Exception e) {
            return content.hashCode() & 0x7FFFFFFFL;
        }
    }

    /** 抽取去重：Redis setIfAbsent（24h），不可用回退进程内 set */
    private boolean markExtracting(String key) {
        if (redisTemplate != null) {
            try {
                Boolean isNew = redisTemplate.opsForValue().setIfAbsent(
                        "kg:extracted:" + key, "1", Duration.ofHours(24));
                return !Boolean.FALSE.equals(isNew);
            } catch (org.springframework.data.redis.RedisSystemException e) {
                log.debug("[MilvusKG] Redis 不可用，回退进程内去重: {}", e.getMessage());
            }
        }
        return extractedKeys.add(key);
    }

    // ── 行结构 ──────────────────────────────────────────────

    private record EntityRow(long id, String name, long relCount) {
    }

    private record TripleRow(String subject, String relation, String object,
                             long count, String question) {
    }
}
