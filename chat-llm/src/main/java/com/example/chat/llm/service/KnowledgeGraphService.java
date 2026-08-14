package com.example.chat.llm.service;

import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.storage.GraphStore;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 知识图谱服务（编排层）—— 管理 Neo4j 连接生命周期，将具体逻辑委托给子服务。
 */
@Service
@ConditionalOnExpression("'${app.knowledge-graph.enabled:false}' == 'true' and '${app.knowledge-graph.backend:neo4j}' == 'neo4j'")
public class KnowledgeGraphService implements GraphStore, KnowledgeGraphFacade {

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

    // 独立 daemon 调度线程：启动竞态导致 Neo4j 连接失败时，每 60s 自动重连，直至成功
    private final ScheduledExecutorService reconnector =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kg-reconnect");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean importing;

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
        connect();
    }

    private void connect() {
        synchronized (this) {
            doConnect();
        }
    }

    private void doConnect() {
        try {
            neo4jDriver = GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUser, neo4jPassword));
            neo4jDriver.verifyConnectivity();
            try (Session session = neo4jDriver.session()) {
                session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (e:Entity) REQUIRE e.name IS UNIQUE");
                session.run("CREATE INDEX IF NOT EXISTS FOR (e:Entity) ON (e.category)");
            }
            log.info("[KnowledgeGraph] Neo4j 连接成功: {}", neo4jUri);
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            log.warn("[KnowledgeGraph] Neo4j 连接失败，知识图谱服务降级，60s 后自动重连: {}", e.getMessage());
            neo4jDriver = null;
            scheduleReconnect();
        }
    }

    // 启动竞态（Neo4j 与 core 同时拉起时）导致连接失败，每 60s 重试直至成功
    private void scheduleReconnect() {
        reconnector.schedule(this::reconnect, 60, TimeUnit.SECONDS);
    }

    private void reconnect() {
        synchronized (this) {
            if (!enabled) return;
            try {
                Driver d = GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUser, neo4jPassword));
                d.verifyConnectivity();
                if (neo4jDriver == null) {
                    neo4jDriver = d;
                    try (Session session = neo4jDriver.session()) {
                        session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (e:Entity) REQUIRE e.name IS UNIQUE");
                        session.run("CREATE INDEX IF NOT EXISTS FOR (e:Entity) ON (e.category)");
                    }
                    log.info("[KnowledgeGraph] Neo4j 自动重连成功: {}", neo4jUri);
                } else {
                    d.close();
                }
            } catch (Exception e) {
                log.warn("[KnowledgeGraph] Neo4j 自动重连失败，60s 后重试: {}", e.getMessage());
                scheduleReconnect();
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (neo4jDriver != null) neo4jDriver.close();
        executor.shutdown();
        reconnector.shutdownNow();
    }

    // ---- 异步抽取 ----

    @Override
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
            } catch (org.springframework.data.redis.RedisSystemException e) {
                log.debug("[KnowledgeGraph] Redis 不可用，跳过去重检查: {}", e.getMessage());
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

    @Override
    public Map<String, Object> getGraph(int limit, int minEntityWeight, int minRelationWeight) {
        return graphRepositoryService.getGraph(neo4jDriver, limit, minEntityWeight, minRelationWeight);
    }

    @Override
    public Map<String, Object> searchEntities(String keyword, int limit, int minEntityWeight, int minRelationWeight) {
        return graphRepositoryService.searchEntities(neo4jDriver, keyword, limit, minEntityWeight, minRelationWeight);
    }

    @Override
    public Map<String, Object> getStats() {
        return graphRepositoryService.getStats(neo4jDriver);
    }

    // ---- 批量导入 ----

    @Override
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

    @Override
    public boolean isImporting() {
        return importing;
    }

    // ── GraphStore SPI 适配（存储平台化：通用图操作入口） ──

    @Override
    public boolean isConnected() {
        return enabled && neo4jDriver != null;
    }

    @Override
    public Map<String, Object> query(String cypher) {
        if (!isConnected() || cypher == null || cypher.isBlank()) {
            return new java.util.HashMap<>();
        }
        try (Session session = neo4jDriver.session()) {
            org.neo4j.driver.Result result = session.run(cypher);
            List<String> columns = result.keys();
            List<List<Object>> rows = new java.util.ArrayList<>();
            result.stream().forEach(record -> {
                List<Object> row = new java.util.ArrayList<>();
                record.values().forEach(v -> row.add(v.asObject()));
                rows.add(row);
            });
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("columns", columns);
            out.put("rows", rows);
            return out;
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] Cypher 查询失败: {}", e.getMessage());
            return new java.util.HashMap<>();
        }
    }

    @Override
    public int saveTriples(String source, List<Map<String, String>> triples) {
        if (!isConnected() || triples == null || triples.isEmpty()) {
            return 0;
        }
        try {
            graphRepositoryService.saveTriples(neo4jDriver, triples, null, source, null);
            return triples.size();
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] SPI saveTriples 失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public String name() {
        return "neo4j";
    }
}
