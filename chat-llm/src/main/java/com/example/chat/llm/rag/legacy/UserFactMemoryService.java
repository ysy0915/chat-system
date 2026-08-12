package com.example.chat.llm.rag.legacy;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.service.LLMInvokeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ConsistencyLevel;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户长期记忆（L2）—— 事实型记忆服务。
 *
 * <p>与 legacy 的 conversation_memory（存整轮对话）不同，本服务在 Milvus 独立
 * <b>user_memory</b> collection 中存储 LLM 抽取的<b>关键事实</b>（如"用户是Java开发"），
 * 每次对话结束后异步抽取向量化入库，用户发起对话时按语义召回注入 System Prompt。</p>
 *
 * <p>Collection 结构：id(自增) / user_id / fact / source_scene / ts / embedding(1024维 COSINE)。</p>
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class UserFactMemoryService {

    private static final Logger log = LoggerFactory.getLogger(UserFactMemoryService.class);

    /** 事实型记忆 Collection（与对话记忆 conversation_memory 区分） */
    public static final String FACT_COLLECTION = "user_memory";

    private final MilvusServiceClient milvusClient;
    private final LegacyEmbeddingService embeddingService;
    private final LLMInvokeService llmInvokeService;
    private final ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;

    @Value("${app.rag.memory.fact-dimension:1024}")
    private int dimension;

    @Value("${app.rag.memory.fact-top-k:5}")
    private int recallTopK;

    @Value("${app.rag.memory.fact-threshold:0.35}")
    private float recallThreshold;

    @Value("${app.rag.memory.fact-provider:qwen}")
    private String factProvider;

    @Value("${app.rag.memory.fact-model:qwen-turbo}")
    private String factModel;

    @Value("${app.rag.memory.fact-max-per-round:5}")
    private int maxFactsPerRound;

    /** Redis 去重集合 key 前缀（事实指纹，避免重复入库） */
    private static final String FACT_SEEN_PREFIX = "memory:fact:seen:";

    @Autowired
    public UserFactMemoryService(@Qualifier("milvusServiceClient") MilvusServiceClient milvusClient,
                                 LegacyEmbeddingService embeddingService,
                                 LLMInvokeService llmInvokeService,
                                 ObjectMapper objectMapper) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.llmInvokeService = llmInvokeService;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对外 API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 异步抽取关键事实并向量化存入 Milvus（对话结束后调用，失败不抛出）。
     *
     * @param scene    场景 personal / treehole / chat
     * @param userId   用户 ID
     * @param question 用户问题
     * @param answer   AI 回答
     */
    public void saveFacts(String scene, Long userId, String question, String answer) {
        if (userId == null || question == null || question.isBlank()) return;
        try {
            ensureCollection();
            List<String> facts = extractFacts(question, answer);
            if (facts.isEmpty()) {
                log.debug("[FactMemory] 本轮无新事实 scene={} user={}", scene, userId);
                return;
            }
            int saved = 0;
            for (String fact : facts) {
                if (isSeen(userId, fact)) continue;
                if (insertFact(userId, fact, scene)) {
                    markSeen(userId, fact);
                    saved++;
                }
            }
            log.info("[FactMemory] 保存完成 scene={} user={} 抽取={} 新增={}", scene, userId, facts.size(), saved);
        } catch (Exception e) {
            log.warn("[FactMemory] 保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    /**
     * 按语义召回用户的相关事实（用于注入 System Prompt）。
     *
     * @param userId   用户 ID
     * @param question 当前问题（查询向量）
     * @return 召回的事实列表（按相关度降序，已按 user_id 过滤）
     */
    public List<String> recallFacts(Long userId, String question, int topK) {
        if (userId == null || question == null || question.isBlank()) return List.of();
        try {
            ensureCollection();
            float[] vec = embeddingService.embed(question);
            if (vec.length == 0) return List.of();

            List<Float> queryVec = new ArrayList<>(vec.length);
            for (float v : vec) queryVec.add(v);

            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(FACT_COLLECTION)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topK > 0 ? topK : recallTopK)
                    .withVectors(List.of(queryVec))
                    .withVectorFieldName("embedding")
                    .withExpr("user_id == " + userId)
                    .withOutFields(List.of("fact", "source_scene", "ts"))
                    .withParams("{\"ef\":64}")
                    .build();

            SearchResults response = milvusClient.search(param).getData();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getResults());
            List<String> facts = new ArrayList<>();
            int rowCount = wrapper.getIDScore(0).size();
            for (int i = 0; i < rowCount; i++) {
                float score = wrapper.getIDScore(0).get(i).getScore();
                if (score < recallThreshold) continue;
                String fact = wrapper.getFieldData("fact", 0).get(i).toString();
                if (fact != null && !fact.isBlank()) {
                    facts.add(fact);
                }
                if (facts.size() >= topK) break;
            }
            return facts;
        } catch (Exception e) {
            log.warn("[FactMemory] 召回失败 user={} error={}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 检查并创建 user_memory collection（幂等）。
     */
    public void ensureCollection() {
        try {
            boolean exists = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(FACT_COLLECTION).build()).getData();
            if (exists) return;

            FieldType idField = FieldType.newBuilder()
                    .withName("id").withDataType(DataType.Int64)
                    .withPrimaryKey(true).withAutoID(true).build();
            FieldType userIdField = FieldType.newBuilder()
                    .withName("user_id").withDataType(DataType.Int64).build();
            FieldType factField = FieldType.newBuilder()
                    .withName("fact").withDataType(DataType.VarChar)
                    .withMaxLength(2048).build();
            FieldType sceneField = FieldType.newBuilder()
                    .withName("source_scene").withDataType(DataType.VarChar)
                    .withMaxLength(64).build();
            FieldType tsField = FieldType.newBuilder()
                    .withName("ts").withDataType(DataType.Int64).build();
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding").withDataType(DataType.FloatVector)
                    .withDimension(dimension).build();

            milvusClient.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(FACT_COLLECTION)
                    .withDescription("用户长期记忆（LLM抽取的关键事实）")
                    .withShardsNum(1)
                    .addFieldType(idField).addFieldType(userIdField).addFieldType(factField)
                    .addFieldType(sceneField).addFieldType(tsField).addFieldType(embeddingField)
                    .build());
            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(FACT_COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.HNSW)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                    .build());
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(FACT_COLLECTION).build());
            log.info("[FactMemory] Collection {} 创建完成 dim={}", FACT_COLLECTION, dimension);
        } catch (Exception e) {
            // 并发创建时报 duplicate，属正常
            log.debug("[FactMemory] ensureCollection: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════════

    /** LLM 抽取关键事实（JSON 数组） */
    @SuppressWarnings("unchecked")
    private List<String> extractFacts(String question, String answer) {
        try {
            String systemPrompt =
                    "你是一名用户记忆分析师，任务是从用户与AI的对话中抽取关于用户的【持久事实】。\n" +
                    "持久事实 = 用户的身份、职业、偏好、习惯、背景等，能在未来对话中帮助AI更好地服务该用户。\n" +
                    "规则：\n" +
                    "1. 只抽取关于用户的明确事实（如\"用户是Java开发者\"、\"用户不喜欢冗长的解释\"）；\n" +
                    "2. 忽略AI回答的内容本身、一次性问题（如时间天气查询）、无信息量的寒暄；\n" +
                    "3. 每条事实为简短的陈述句（≤30字），不得出现\"用户说\"\"用户表示\"等前缀；\n" +
                    "4. 最多抽取5条；没有可抽取的事实则输出空数组 [];\n" +
                    "5. 仅输出JSON数组字符串，不要输出任何解释或多余文字。";

            String userContent = "用户的发言：\n" + question +
                    "\n\nAI的回应：\n" + (answer != null ? answer : "");

            LangChainRequest req = new LangChainRequest();
            req.setBizType("RAG");
            req.setProvider(factProvider);
            req.setModel(factModel);
            req.setTemperature(0.1);
            req.setMaxTokens(500);
            req.setMessages(List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            ));

            LangChainResponse resp = llmInvokeService.invoke(req);
            if (!resp.isSuccess() || resp.getContent() == null || resp.getContent().isBlank()) {
                log.warn("[FactMemory] 抽取失败 provider={} error={}", factProvider, resp.getError());
                return List.of();
            }
            String content = resp.getContent().trim();
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();

            List<Object> raw = objectMapper.readValue(content.substring(start, end + 1), List.class);
            List<String> facts = new ArrayList<>();
            for (Object o : raw) {
                String s = String.valueOf(o).trim();
                if (s.length() > 2 && s.length() <= 60 && !facts.contains(s)) {
                    facts.add(s);
                }
                if (facts.size() >= maxFactsPerRound) break;
            }
            return facts;
        } catch (Exception e) {
            log.warn("[FactMemory] 抽取解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 插入单条事实到 Milvus */
    private boolean insertFact(Long userId, String fact, String scene) {
        try {
            float[] vec = embeddingService.embed(fact);
            if (vec.length == 0) return false;
            List<Float> vector = new ArrayList<>(vec.length);
            for (float v : vec) vector.add(v);

            List<Long> userIds = List.of(userId);
            List<String> facts = List.of(fact);
            List<String> scenes = List.of(scene != null ? scene : "personal");
            List<Long> ts = List.of(System.currentTimeMillis());
            List<List<Float>> embeddings = List.of(vector);

            java.util.List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("user_id", userIds));
            fields.add(new InsertParam.Field("fact", facts));
            fields.add(new InsertParam.Field("source_scene", scenes));
            fields.add(new InsertParam.Field("ts", ts));
            fields.add(new InsertParam.Field("embedding", embeddings));
            milvusClient.insert(InsertParam.newBuilder()
                    .withCollectionName(FACT_COLLECTION)
                    .withFields(fields)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("[FactMemory] 插入失败 user={} fact={} error={}", userId, fact, e.getMessage());
            return false;
        }
    }

    /** 事实指纹：sha256 前 12 位 */
    private String factDigest(Long userId, String fact) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((userId + "|" + fact).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(fact.hashCode());
        }
    }

    private boolean isSeen(Long userId, String fact) {
        if (redisTemplate == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet()
                    .isMember(FACT_SEEN_PREFIX + userId, factDigest(userId, fact)));
        } catch (Exception e) {
            return false;
        }
    }

    private void markSeen(Long userId, String fact) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForSet().add(FACT_SEEN_PREFIX + userId, factDigest(userId, fact));
            redisTemplate.expire(FACT_SEEN_PREFIX + userId, 180, TimeUnit.DAYS);
        } catch (Exception ignored) {
        }
    }
}
