package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.dto.LLMMessage;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 知识图谱服务
 *
 * 功能：
 * 1. 用 LLM 从问答对话中抽取知识三元组 (实体, 关系, 实体)
 * 2. 过滤隐私和情绪类数据
 * 3. 写入 Neo4j 图数据库
 * 4. 提供图谱查询接口
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private static final int QUESTION_TRUNCATE_LENGTH = 500;
    private static final int ANSWER_TRUNCATE_LENGTH = 2000;
    private static final int KG_BATCH_SIZE = 20;

    private final ObjectMapper objectMapper;
    private final com.example.chat.repository.MessageRepository messageRepository;
    private final com.example.chat.repository.DebateRecordRepository debateRecordRepository;
    private final com.example.chat.repository.ModelConfigRepository modelConfigRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final BaseUrlResolver baseUrlResolver;
    private final LlmConfigProperties llmConfig;
    private final DirectLLMClient directLLMClient;

    @Value("${spring.neo4j.uri:bolt://127.0.0.1:7687}")
    private String neo4jUri;

    @Value("${spring.neo4j.authentication.username:neo4j}")
    private String neo4jUser;

    @Value("${spring.neo4j.authentication.password:}")
    private String neo4jPassword;

    @Value("${app.knowledge-graph.enabled:false}")
    private boolean enabled;

    private Driver neo4jDriver;

    /** LLM 统一调用入口（可选注入，core模块才有） */
    @Autowired(required = false)
    private LLMInvoker llmInvoker;

    /** 异步抽取线程池（单线程，避免并发打 LLM） */
    private final ExecutorService executor =
            ThreadPoolFactory.create(1, 1, 200, "kg-extractor");

    public KnowledgeGraphService(ObjectMapper objectMapper,
                                  com.example.chat.repository.MessageRepository messageRepository,
                                  com.example.chat.repository.DebateRecordRepository debateRecordRepository,
                                  com.example.chat.repository.ModelConfigRepository modelConfigRepository,
                                  LlmConfigProperties llmConfig,
                                  BaseUrlResolver baseUrlResolver,
                                  DirectLLMClient directLLMClient,
                                  @Autowired(required = false) org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.debateRecordRepository = debateRecordRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.llmConfig = llmConfig;
        this.baseUrlResolver = baseUrlResolver;
        this.directLLMClient = directLLMClient;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[KnowledgeGraph] 未启用知识图谱服务");
            return;
        }
        try {
            neo4jDriver = GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUser, neo4jPassword));
            neo4jDriver.verifyConnectivity();
            log.info("[KnowledgeGraph] Neo4j 连接成功: {}", neo4jUri);

            // 创建约束和索引
            try (Session session = neo4jDriver.session()) {
                session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (e:Entity) REQUIRE e.name IS UNIQUE");
                session.run("CREATE INDEX IF NOT EXISTS FOR (e:Entity) ON (e.category)");
            }

        } catch (Exception e) {
            log.warn("[KnowledgeGraph] Neo4j 连接失败，知识图谱服务降级: {}", e.getMessage());
            neo4jDriver = null;
        }
    }

    @PreDestroy
    public void destroy() {
        if (neo4jDriver != null) {
            neo4jDriver.close();
        }
        executor.shutdown();
    }

    /**
     * 异步抽取知识三元组并写入 Neo4j
     *
     * @param messageId 消息ID（用于溯源）
     * @param question  用户问题
     * @param answer    AI回答
     * @param source    来源：chat / debate / personal
     */
    public void extractAndSaveAsync(Long messageId, String question, String answer, String source) {
        if (!enabled || neo4jDriver == null) return;
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) return;

        // 幂等检查：防止双 core 实例重复抽取同一条消息
        if (messageId != null) {
            String key = "kg:extracted:" + source + ":" + messageId;
            try {
                Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", java.time.Duration.ofHours(24));
                if (Boolean.FALSE.equals(isNew)) {
                    log.debug("[KnowledgeGraph] 消息 {} 已抽取过，跳过", messageId);
                    return;
                }
            } catch (Exception e) {
                // Redis 不可用时不阻塞，可能重复抽取但 Neo4j MERGE 幂等
            }
        }

        executor.submit(() -> {
            try {
                List<Map<String, String>> triples = extractTriples(question, answer);
                if (triples.isEmpty()) return;

                saveTriples(triples, messageId, source, question);
                log.info("[KnowledgeGraph] 抽取 {} 个三元组 from msg={} source={}", triples.size(), messageId, source);
            } catch (Exception e) {
                log.warn("[KnowledgeGraph] 抽取失败 msg={}: {}", messageId, e.getMessage());
            }
        });
    }

    /**
     * 调用 LLM 抽取知识三元组
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractTriples(String question, String answer) {
        // 截断过长文本
        String q = question.length() > QUESTION_TRUNCATE_LENGTH ? question.substring(0, QUESTION_TRUNCATE_LENGTH) : question;
        String a = answer.length() > ANSWER_TRUNCATE_LENGTH ? answer.substring(0, ANSWER_TRUNCATE_LENGTH) : answer;

        String prompt = """
            你是一个知识抽取专家。从以下问答中抽取知识三元组（实体-关系-实体）。

            规则：
            1. 只抽取客观知识、概念、技术、因果关系，不抽取情绪、感受、个人隐私
            2. 不抽取人名、邮箱、手机号、地址等隐私信息
            3. 每条三元组包含 subject（主体）、relation（关系）、object（客体）
            4. 返回 JSON 格式，不要有多余内容

            示例：
            {"triples": [{"subject":"实体1","relation":"关系","object":"实体2"}]}

            问题：%s
            回答：%s
            """.formatted(q, a);

        // 优先通过 LLMInvoker 统一调用（享受熔断、重试、自愈、统计能力）
        String content = null;
        try {
            if (llmInvoker != null) {
                com.example.chat.entity.ModelConfig config = resolveModelConfig();
                if (config != null) {
                    content = llmInvoker.invoke(config, prompt, 0.1, "knowledge-graph", llmConfig.getBaseUrl(), llmConfig.getApiKey());
                }
            }
            // LLMInvoker 不可用时，降级为直接 HTTP 调用
            if (content == null) {
                content = callLLMDirect(prompt);
            }
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] LLM 抽取异常: {}", e.getMessage());
            return List.of();
        }

        if (content == null || content.isBlank()) {
            return List.of();
        }

        try {
            // 提取 JSON（兼容 markdown code block）
            content = content.trim();
            if (content.contains("```")) {
                int start = content.indexOf("{");
                int end = content.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    content = content.substring(start, end + 1);
                }
            }

            Map<String, Object> result = objectMapper.readValue(content, Map.class);
            List<Map<String, Object>> triples = (List<Map<String, Object>>) result.get("triples");
            if (triples == null) return List.of();

            List<Map<String, String>> parsed = new ArrayList<>();
            for (Map<String, Object> t : triples) {
                String subject = (String) t.get("subject");
                String relation = (String) t.get("relation");
                String object = (String) t.get("object");
                if (subject != null && !subject.isBlank() && relation != null && !relation.isBlank()
                        && object != null && !object.isBlank()) {
                    parsed.add(Map.of(
                            "subject", subject.trim(),
                            "relation", relation.trim(),
                            "object", object.trim()
                    ));
                }
            }
            return parsed;
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 解析三元组JSON失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析模型配置（供 LLMInvoker 使用）
     */
    private com.example.chat.entity.ModelConfig resolveModelConfig() {
        try {
            List<com.example.chat.entity.ModelConfig> configs = modelConfigRepository.findAllEnabledByType("chat");
            if (configs == null || configs.isEmpty()) return null;
            return configs.stream()
                    .filter(c -> "qwen".equalsIgnoreCase(c.provider) || "deepseek".equalsIgnoreCase(c.provider))
                    .findFirst()
                    .orElse(configs.get(0));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 获取模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 降级方案：直接 HTTP 调用 LLM（LLMInvoker 不可用时使用）。
     * 数据库模型配置选择逻辑保留在此，实际 HTTP 调用委托给 DirectLLMClient。
     */
    private String callLLMDirect(String prompt) {
        String apiKey = llmConfig.getApiKey();
        String baseUrl = llmConfig.getBaseUrl();
        String model = llmConfig.getModel();

        try {
            List<com.example.chat.entity.ModelConfig> configs = modelConfigRepository.findAllEnabledByType("chat");
            if (configs != null && !configs.isEmpty()) {
                com.example.chat.entity.ModelConfig chosen = configs.stream()
                        .filter(c -> "qwen".equalsIgnoreCase(c.provider) || "dashscope".equalsIgnoreCase(c.provider))
                        .findFirst()
                        .orElse(configs.get(0));
                if (chosen.apiKeyEncrypted != null && !chosen.apiKeyEncrypted.isBlank()) {
                    apiKey = chosen.apiKeyEncrypted;
                }
                baseUrl = baseUrlResolver.resolve(chosen, baseUrl);
                if (chosen.model != null && !chosen.model.isBlank()) model = chosen.model;
            }
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 获取模型配置失败，使用默认配置: {}", e.getMessage());
        }

        try {
            return directLLMClient.call(baseUrl, apiKey, model,
                    List.of(LLMMessage.system("你是知识抽取助手，只返回JSON。"), LLMMessage.user(prompt)),
                    0.1, -1);
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] LLM 直接调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将三元组写入 Neo4j
     */
    private void saveTriples(List<Map<String, String>> triples, Long messageId, String source, String question) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                for (Map<String, String> triple : triples) {
                    String subject = triple.get("subject");
                    String relation = triple.get("relation");
                    String object = triple.get("object");

                    // MERGE 主体实体
                    tx.run("MERGE (s:Entity {name: $name}) " +
                                   "SET s.updatedAt = datetime()",
                            Map.of("name", subject));

                    // MERGE 客体实体
                    tx.run("MERGE (o:Entity {name: $name}) " +
                                   "SET o.updatedAt = datetime()",
                            Map.of("name", object));

                    // MERGE 关系
                    tx.run("MATCH (s:Entity {name: $subject}), (o:Entity {name: $object}) " +
                                   "MERGE (s)-[r:RELATION {type: $relType}]->(o) " +
                                   "SET r.source = $source, r.messageId = $msgId, " +
                                   "r.question = $question, r.updatedAt = datetime()",
                            Map.of(
                                    "subject", subject,
                                    "object", object,
                                    "relType", relation,
                                    "source", source,
                                    "msgId", messageId,
                                    "question", question != null && question.length() > 200 ? question.substring(0, 200) : question
                            ));
                }
                return null;
            });
        }
    }

    /**
     * 查询图谱数据（用于前端可视化）。
     * 返回所有节点和边（按关系数量排序限制数量）。
     *
     * @param limit 返回节点数量上限
     * @return 包含nodes（节点列表）和edges（边列表）的Map，Neo4j不可用时返回空列表
     */
    public Map<String, Object> getGraph(int limit) {
        if (neo4jDriver == null) return Map.of("nodes", List.of(), "edges", List.of());

        try (Session session = neo4jDriver.session()) {
            // 查询节点（按关系数量排序，取 top N）
            Result nodeResult = session.run(
                    "MATCH (e:Entity)-[r]-() " +
                            "WITH e, count(r) as relCount " +
                            "ORDER BY relCount DESC LIMIT $limit " +
                            "RETURN id(e) as id, e.name as name, relCount",
                    Map.of("limit", limit));

            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<Long> nodeIds = new HashSet<>();
            while (nodeResult.hasNext()) {
                org.neo4j.driver.Record record = nodeResult.next();
                long id = record.get("id").asLong();
                nodeIds.add(id);
                nodes.add(Map.of(
                        "id", id,
                        "label", record.get("name").asString(),
                        "value", record.get("relCount").asInt()
                ));
            }

            // 查询这些节点之间的边
            if (nodeIds.isEmpty()) return Map.of("nodes", List.of(), "edges", List.of());

            Result edgeResult = session.run(
                    "MATCH (s:Entity)-[r:RELATION]->(o:Entity) " +
                            "WHERE id(s) IN $ids AND id(o) IN $ids " +
                            "RETURN id(s) as source, id(o) as target, r.type as type, r.question as question",
                    Map.of("ids", nodeIds));

            List<Map<String, Object>> edges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                org.neo4j.driver.Record record = edgeResult.next();
                Map<String, Object> edge = new HashMap<>();
                edge.put("source", record.get("source").asLong());
                edge.put("target", record.get("target").asLong());
                edge.put("label", record.get("type").asString());
                String question = record.get("question").isNull() ? null : record.get("question").asString();
                if (question != null) edge.put("question", question);
                edges.add(edge);
            }

            return Map.of("nodes", nodes, "edges", edges);
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 查询图谱失败: {}", e.getMessage());
            return Map.of("nodes", List.of(), "edges", List.of());
        }
    }

    /**
     * 搜索实体
     */
    public Map<String, Object> searchEntities(String keyword, int limit) {
        if (neo4jDriver == null) return Map.of("nodes", List.of(), "edges", List.of());

        try (Session session = neo4jDriver.session()) {
            Result nodeResult = session.run(
                    "MATCH (e:Entity) WHERE e.name CONTAINS $kw " +
                            "WITH e LIMIT $limit " +
                            "MATCH (e)-[r]-() " +
                            "WITH e, count(r) as relCount " +
                            "RETURN id(e) as id, e.name as name, relCount",
                    Map.of("kw", keyword, "limit", limit));

            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<Long> nodeIds = new HashSet<>();
            while (nodeResult.hasNext()) {
                org.neo4j.driver.Record record = nodeResult.next();
                long id = record.get("id").asLong();
                nodeIds.add(id);
                nodes.add(Map.of(
                        "id", id,
                        "label", record.get("name").asString(),
                        "value", record.get("relCount").asInt()
                ));
            }

            if (nodeIds.isEmpty()) return Map.of("nodes", List.of(), "edges", List.of());

            // 查询这些节点的一跳邻居
            Result neighborResult = session.run(
                    "MATCH (e:Entity)-[r:RELATION]-(o:Entity) " +
                            "WHERE id(e) IN $ids " +
                            "RETURN id(o) as id, o.name as name, count(r) as relCount",
                    Map.of("ids", nodeIds));

            while (neighborResult.hasNext()) {
                org.neo4j.driver.Record record = neighborResult.next();
                long id = record.get("id").asLong();
                if (!nodeIds.contains(id)) {
                    nodeIds.add(id);
                    nodes.add(Map.of(
                            "id", id,
                            "label", record.get("name").asString(),
                            "value", record.get("relCount").asInt()
                    ));
                }
            }

            // 查询边
            Result edgeResult = session.run(
                    "MATCH (s:Entity)-[r:RELATION]->(o:Entity) " +
                            "WHERE id(s) IN $ids AND id(o) IN $ids " +
                            "RETURN id(s) as source, id(o) as target, r.type as type, r.question as question",
                    Map.of("ids", nodeIds));

            List<Map<String, Object>> edges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                org.neo4j.driver.Record record = edgeResult.next();
                Map<String, Object> edge = new HashMap<>();
                edge.put("source", record.get("source").asLong());
                edge.put("target", record.get("target").asLong());
                edge.put("label", record.get("type").asString());
                String question = record.get("question").isNull() ? null : record.get("question").asString();
                if (question != null) edge.put("question", question);
                edges.add(edge);
            }

            return Map.of("nodes", nodes, "edges", edges);
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 搜索实体失败: {}", e.getMessage());
            return Map.of("nodes", List.of(), "edges", List.of());
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        if (neo4jDriver == null) return Map.of("entityCount", 0, "relationCount", 0);

        try (Session session = neo4jDriver.session()) {
            long entityCount = session.run("MATCH (e:Entity) RETURN count(e) as cnt").single().get("cnt").asLong();
            long relationCount = session.run("MATCH ()-[r:RELATION]->() RETURN count(r) as cnt").single().get("cnt").asLong();
            return Map.of("entityCount", entityCount, "relationCount", relationCount);
        } catch (Exception e) {
            return Map.of("entityCount", 0, "relationCount", 0);
        }
    }

    // ==================== 批量导入 ====================

    private volatile boolean importing = false;

    /**
     * 批量导入历史问答数据到知识图谱
     * 异步执行，返回是否已开始
     */
    public boolean startBatchImport() {
        if (!enabled || neo4jDriver == null) return false;
        if (importing) return false;
        importing = true;

        executor.submit(() -> {
            try {
                doBatchImport();
            } catch (Exception e) {
                log.error("[KnowledgeGraph] 批量导入失败: {}", e.getMessage(), e);
            } finally {
                importing = false;
            }
        });
        return true;
    }

    public boolean isImporting() {
        return importing;
    }

    @SuppressWarnings("unchecked")
    private void doBatchImport() {
        int batchSize = KG_BATCH_SIZE;
        int offset = 0;
        int totalProcessed = 0;
        int totalTriples = 0;

        while (true) {
            List<com.example.chat.entity.Message> messages = messageRepository.findAllWithAnswers(offset, batchSize);
            if (messages.isEmpty()) break;

            for (com.example.chat.entity.Message m : messages) {
                try {
                    String answer = parseAnswer(m.answerJson);
                    if (answer == null || answer.isBlank()) continue;

                    List<Map<String, String>> triples = extractTriples(m.question, answer);
                    if (!triples.isEmpty()) {
                        String source = (m.isPrivate != null && m.isPrivate == 1) ? "personal" : "chat";
                        saveTriples(triples, m.id, source, m.question);
                        totalTriples += triples.size();
                    }
                    totalProcessed++;
                } catch (Exception e) {
                    log.warn("[KnowledgeGraph] 导入消息 {} 失败: {}", m.id, e.getMessage());
                }
            }

            offset += batchSize;
            log.info("[KnowledgeGraph] 批量导入进度: 已处理 {}/{} 条, 三元组 {}", totalProcessed, offset, totalTriples);

            // 控制速率，避免打爆 LLM API
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        // 导入辩论记录
        try {
            List<com.example.chat.entity.DebateRecord> debates = debateRecordRepository.findAll();
            int debateProcessed = 0;
            int debateTriples = 0;
            for (com.example.chat.entity.DebateRecord dr : debates) {
                try {
                    if (dr.finalAnswer == null || dr.finalAnswer.isBlank()) continue;
                    List<Map<String, String>> triples = extractTriples(dr.question, dr.finalAnswer);
                    if (!triples.isEmpty()) {
                        saveTriples(triples, (long) dr.id, "debate", dr.question);
                        debateTriples += triples.size();
                    }
                    debateProcessed++;
                } catch (Exception e) {
                    log.warn("[KnowledgeGraph] 导入辩论 {} 失败: {}", dr.id, e.getMessage());
                }
            }
            log.info("[KnowledgeGraph] 辩论导入完成: 处理 {} 条, 三元组 {}", debateProcessed, debateTriples);
            totalProcessed += debateProcessed;
            totalTriples += debateTriples;
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 导入辩论记录失败: {}", e.getMessage());
        }

        log.info("[KnowledgeGraph] 批量导入完成! 共处理 {} 条记录, 抽取 {} 个三元组", totalProcessed, totalTriples);
    }

    private String parseAnswer(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(answerJson, Map.class);
            Object answer = parsed.get("answer");
            return answer != null ? answer.toString() : null;
        } catch (Exception e) {
            // 可能是纯文本
            return answerJson;
        }
    }
}
