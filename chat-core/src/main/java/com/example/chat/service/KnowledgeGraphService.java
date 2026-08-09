package com.example.chat.service;

import com.example.chat.config.ThreadPoolFactory;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 知识图谱服务（编排层）—— 管理 Neo4j 连接生命周期，将具体逻辑委托给子服务。
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final TripleExtractionService tripleExtractionService;
    private final GraphRepositoryService graphRepositoryService;
    private final BatchImportService batchImportService;
    private final StringRedisTemplate redisTemplate;

    @Value("${spring.neo4j.uri:bolt://127.0.0.1:7687}")
    private String neo4jUri;

    @Value("${spring.neo4j.authentication.username:neo4j}")
    private String neo4jUser;

    @Value("${spring.neo4j.authentication.password:}")
    private String neo4jPassword;

    @Value("${app.knowledge-graph.enabled:false}")
    private boolean enabled;

    private Driver neo4jDriver;

    private final ExecutorService executor =
            ThreadPoolFactory.create(1, 1, 200, "kg-extractor");

    private volatile boolean importing = false;

    public KnowledgeGraphService(TripleExtractionService tripleExtractionService,
                                  GraphRepositoryService graphRepositoryService,
                                  BatchImportService batchImportService,
                                  @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.tripleExtractionService = tripleExtractionService;
        this.graphRepositoryService = graphRepositoryService;
        this.batchImportService = batchImportService;
        this.redisTemplate = redisTemplate;
    }

    // ---- 生命周期 ----

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
        if (neo4jDriver != null) neo4jDriver.close();
        executor.shutdown();
    }

    // ---- 异步抽取 ----

    public void extractAndSaveAsync(Long messageId, String question, String answer, String source) {
        if (!enabled || neo4jDriver == null) return;
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) return;

        if (messageId != null) {
            String key = "kg:extracted:" + source + ":" + messageId;
            try {
                Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(24));
                if (Boolean.FALSE.equals(isNew)) {
                    log.debug("[KnowledgeGraph] 消息 {} 已抽取过，跳过", messageId);
                    return;
                }
            } catch (Exception e) {
                // Redis 不可用时继续，Neo4j MERGE 幂等
            }
        }

        executor.submit(() -> {
            try {
                List<Map<String, String>> triples = tripleExtractionService.extractTriples(question, answer);
                if (triples.isEmpty()) return;
                graphRepositoryService.saveTriples(neo4jDriver, triples, messageId, source, question);
                log.info("[KnowledgeGraph] 抽取 {} 个三元组 from msg={} source={}", triples.size(), messageId, source);
            } catch (Exception e) {
                log.warn("[KnowledgeGraph] 抽取失败 msg={}: {}", messageId, e.getMessage());
            }
        });
    }

    // ---- 图谱查询（委托） ----

    public Map<String, Object> getGraph(int limit) {
        return graphRepositoryService.getGraph(neo4jDriver, limit);
    }

    public Map<String, Object> searchEntities(String keyword, int limit) {
        return graphRepositoryService.searchEntities(neo4jDriver, keyword, limit);
    }

    public Map<String, Object> getStats() {
        return graphRepositoryService.getStats(neo4jDriver);
    }

    // ---- 批量导入 ----

    public boolean startBatchImport() {
        if (!enabled || neo4jDriver == null) return false;
        if (importing) return false;
        importing = true;

        executor.submit(() -> {
            try {
                batchImportService.execute(neo4jDriver);
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
}
