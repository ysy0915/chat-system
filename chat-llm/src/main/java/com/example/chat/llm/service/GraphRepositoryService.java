package com.example.chat.llm.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱持久化服务 —— 封装所有 Neo4j 读写操作（纯 Cypher 层），不涉及 LLM。
 */
@Service
public class GraphRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(GraphRepositoryService.class);

    /** 将三元组写入 Neo4j */
    public void saveTriples(Driver neo4jDriver, List<Map<String, String>> triples,
                            Long messageId, String source, String question) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                for (Map<String, String> triple : triples) {
                    String subject = triple.get("subject");
                    String relation = triple.get("relation");
                    String object = triple.get("object");

                    tx.run("MERGE (s:Entity {name: $name}) SET s.updatedAt = datetime()",
                            Map.of("name", subject));
                    tx.run("MERGE (o:Entity {name: $name}) SET o.updatedAt = datetime()",
                            Map.of("name", object));
                    tx.run("""
                            MATCH (s:Entity {name: $subject}), (o:Entity {name: $object})
                            MERGE (s)-[r:RELATION {type: $relType}]->(o)
                            ON CREATE SET r.count = 1, r.source = $source, r.messageId = $msgId,
                                r.question = $question, r.updatedAt = datetime()
                            ON MATCH SET r.count = coalesce(r.count, 1) + 1, r.updatedAt = datetime(),
                                r.source = coalesce(r.source, $source), r.messageId = $msgId
                            """,
                            Map.of("subject", subject, "object", object,
                                    "relType", relation, "source", source,
                                    "msgId", messageId, "question",
                                    question != null && question.length() > 200
                                            ? question.substring(0, 200) : question));
                }
                return null;
            });
        }
    }

    /** 图谱可视化查询：返回 top N 节点（权重 >= minEntityWeight）及其间的边（权重 >= minRelationWeight） */
    public Map<String, Object> getGraph(Driver neo4jDriver, int limit, int minEntityWeight, int minRelationWeight) {
        if (neo4jDriver == null) return Map.of("nodes", List.of(), "edges", List.of());
        try (Session session = neo4jDriver.session()) {
            Result nodeResult = session.run(
                    "MATCH (e:Entity)-[r]-() WITH e, count(r) as relCount " +
                    "WHERE relCount >= $minEntityWeight " +
                    "ORDER BY relCount DESC LIMIT $limit RETURN id(e) as id, e.name as name, relCount",
                    Map.of("limit", limit, "minEntityWeight", minEntityWeight));

            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<Long> nodeIds = new HashSet<>();
            while (nodeResult.hasNext()) {
                Record record = nodeResult.next();
                long id = record.get("id").asLong();
                nodeIds.add(id);
                nodes.add(Map.of("id", id, "label", record.get("name").asString(),
                        "value", record.get("relCount").asInt()));
            }
            if (nodeIds.isEmpty()) return Map.of("nodes", List.of(), "edges", List.of());

            Result edgeResult = session.run(
                    "MATCH (s:Entity)-[r:RELATION]->(o:Entity) WHERE id(s) IN $ids AND id(o) IN $ids " +
                    "AND coalesce(r.count, 1) >= $minRelationWeight " +
                    "RETURN id(s) as source, id(o) as target, r.type as type, r.question as question, coalesce(r.count, 1) as weight",
                    Map.of("ids", nodeIds, "minRelationWeight", minRelationWeight));

            List<Map<String, Object>> edges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                Record record = edgeResult.next();
                Map<String, Object> edge = new HashMap<>();
                edge.put("source", record.get("source").asLong());
                edge.put("target", record.get("target").asLong());
                edge.put("label", record.get("type").asString());
                edge.put("weight", record.get("weight").asInt());
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

    /** 搜索实体 */
    public Map<String, Object> searchEntities(Driver neo4jDriver, String keyword, int limit, int minEntityWeight, int minRelationWeight) {
        if (neo4jDriver == null) return Map.of("nodes", List.of(), "edges", List.of());
        try (Session session = neo4jDriver.session()) {
            Result nodeResult = session.run(
                    "MATCH (e:Entity) WHERE e.name CONTAINS $kw WITH e LIMIT $limit " +
                    "MATCH (e)-[r]-() WITH e, count(r) as relCount WHERE relCount >= $minEntityWeight " +
                    "RETURN id(e) as id, e.name as name, relCount",
                    Map.of("kw", keyword, "limit", limit, "minEntityWeight", minEntityWeight));

            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<Long> nodeIds = new HashSet<>();
            while (nodeResult.hasNext()) {
                Record record = nodeResult.next();
                long id = record.get("id").asLong();
                nodeIds.add(id);
                nodes.add(Map.of("id", id, "label", record.get("name").asString(),
                        "value", record.get("relCount").asInt()));
            }
            if (nodeIds.isEmpty()) return Map.of("nodes", List.of(), "edges", List.of());

            // 一跳邻居
            Result neighborResult = session.run(
                    "MATCH (e:Entity)-[r:RELATION]-(o:Entity) WHERE id(e) IN $ids " +
                    "RETURN id(o) as id, o.name as name, count(r) as relCount",
                    Map.of("ids", nodeIds));
            while (neighborResult.hasNext()) {
                Record record = neighborResult.next();
                long id = record.get("id").asLong();
                if (!nodeIds.contains(id)) {
                    nodeIds.add(id);
                    nodes.add(Map.of("id", id, "label", record.get("name").asString(),
                            "value", record.get("relCount").asInt()));
                }
            }

            // 边
            Result edgeResult = session.run(
                    "MATCH (s:Entity)-[r:RELATION]->(o:Entity) WHERE id(s) IN $ids AND id(o) IN $ids " +
                    "AND coalesce(r.count, 1) >= $minRelationWeight " +
                    "RETURN id(s) as source, id(o) as target, r.type as type, r.question as question, coalesce(r.count, 1) as weight",
                    Map.of("ids", nodeIds, "minRelationWeight", minRelationWeight));
            List<Map<String, Object>> edges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                Record record = edgeResult.next();
                Map<String, Object> edge = new HashMap<>();
                edge.put("source", record.get("source").asLong());
                edge.put("target", record.get("target").asLong());
                edge.put("label", record.get("type").asString());
                edge.put("weight", record.get("weight").asInt());
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

    /** 统计信息 */
    public Map<String, Object> getStats(Driver neo4jDriver) {
        if (neo4jDriver == null) return Map.of("entityCount", 0, "relationCount", 0);
        try (Session session = neo4jDriver.session()) {
            long entityCount = session.run("MATCH (e:Entity) RETURN count(e) as cnt").single().get("cnt").asLong();
            long relationCount = session.run("MATCH ()-[r:RELATION]->() RETURN count(r) as cnt").single().get("cnt").asLong();
            return Map.of("entityCount", entityCount, "relationCount", relationCount);
        } catch (Exception e) {
            return Map.of("entityCount", 0, "relationCount", 0);
        }
    }
}
