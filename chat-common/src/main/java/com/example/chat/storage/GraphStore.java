package com.example.chat.storage;

import java.util.List;
import java.util.Map;

/**
 * <h2>图数据库 SPI 接口（Neo4j / NebulaGraph / JanusGraph… 热插拔）</h2>
 *
 * <p>[B档] 存储平台化抽象。通用图操作：连通性探测 / Cypher 查询 / 三元组批量写入。
 * 现有 {@code chat-llm} 的 {@code KnowledgeGraphService}（Neo4j）实现本接口，
 * 经 {@link StorageRegistry} 注册为 type={@code graph}。</p>
 *
 * @see Storage
 */
public interface GraphStore extends Storage {

    /** 存储大类类型标识 */
    @Override
    default String type() {
        return "graph";
    }

    /**
     * 连通性探测（连接是否建立成功）。
     */
    boolean isConnected();

    /**
     * 执行 Cypher 查询并返回结果（含 columns + rows）。
     * 实现应吞掉查询异常并以空结果返回，避免上抛到业务链路。
     *
     * @param cypher Cypher 语句
     * @return 查询结果 map（columns / rows），失败返回空 map
     */
    Map<String, Object> query(String cypher);

    /**
     * 批量写入三元组（(头实体)-[关系]->(尾实体)）。
     *
     * @param source  来源标记（消息 ID / 场景）
     * @param triples 三元组列表（键约定由实现定义，如 Neo4j 实现用 subject/relation/object）
     * @return 写入条数；存储不可用时返回 0
     */
    int saveTriples(String source, List<Map<String, String>> triples);
}
