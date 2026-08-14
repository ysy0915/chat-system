package com.example.chat.llm.service;

import java.util.Map;

/**
 * <h2>知识图谱门面接口</h2>
 *
 * <p>知识图谱控制器（{@code KnowledgeGraphController}）依赖本接口而非具体实现：
 * <ul>
 *   <li>Neo4j 实现：{@link KnowledgeGraphService}（<code>app.knowledge-graph.backend=neo4j</code>，默认）</li>
 *   <li>纯内存实现：{@link InMemoryKnowledgeGraphService}（<code>app.knowledge-graph.backend=memory</code>，
 *       standalone 模式零基础设施依赖）</li>
 * </ul>
 * </p>
 */
public interface KnowledgeGraphFacade {

    /** 图谱可视化：top N 节点及其间权重达标的边。 */
    Map<String, Object> getGraph(int limit, int minEntityWeight, int minRelationWeight);

    /** 按关键词搜索实体及其一跳邻居。 */
    Map<String, Object> searchEntities(String keyword, int limit, int minEntityWeight, int minRelationWeight);

    /** 统计信息：实体数 / 关系数。 */
    Map<String, Object> getStats();

    /** 异步触发三元组抽取入库（fire-and-forget）。 */
    void extractAndSaveAsync(Long messageId, String question, String answer, String source);

    /** 触发批量导入历史数据。 */
    boolean startBatchImport();

    /** 是否正在批量导入。 */
    boolean isImporting();
}
