package com.example.chat.llm.rag.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>RAG 数据源注册中心 — 多数据源路由</h2>
 *
 * <p>管理多个 {@link DataSource}，按名称或类型查找。
 *
 * <h3>路由优先级</h3>
 * <ol>
 *   <li>调用方明确指定 dataSourceName → 精确匹配</li>
 *   <li>调用方未指定 → 默认数据源</li>
 *   <li>无默认 → 第一个可用数据源</li>
 * </ol>
 *
 * <h3>多数据源切换示例</h3>
 * <pre>
 * {@code
 * // API 请求中指定数据源
 * POST /api/v1/llm/rag/invoke
 * { "query": "...", "dataSource": "project-kb" }
 *
 * // Registry 自动路由到正确的向量库 + Embedding + LLM
 * }
 * </pre>
 */
@Component
public class DataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    private final Map<String, DataSource> sources = new ConcurrentHashMap<>();

    // ── 注册/移除 ────────────────────────────────────────

    public void register(DataSource ds) {
        sources.put(ds.getName(), ds);
        log.info("[DataSourceRegistry] 注册数据源: {} (isDefault={})", ds.getName(), ds.isDefault());
    }

    public void unregister(String name) {
        DataSource removed = sources.remove(name);
        if (removed != null) {
            log.info("[DataSourceRegistry] 移除数据源: {}", name);
        }
    }

    // ── 路由 ──────────────────────────────────────────────

    /** 按名称精确查找 */
    public DataSource get(String name) {
        DataSource ds = sources.get(name);
        if (ds != null && ds.isHealthy()) return ds;
        return null;
    }

    /** 获取默认数据源 */
    public DataSource getDefault() {
        // 优先返回标记为 default 的
        return sources.values().stream()
                .filter(DataSource::isHealthy)
                .filter(DataSource::isDefault)
                .min((a, b) -> a.getPriority() - b.getPriority())
                .orElseGet(() -> getFirst());
    }

    /** 获取第一个可用的 */
    public DataSource getFirst() {
        return sources.values().stream()
                .filter(DataSource::isHealthy)
                .min((a, b) -> a.getPriority() - b.getPriority())
                .orElseThrow(() -> new IllegalStateException("没有可用的 RAG 数据源"));
    }

    /** 按 sourceType 列出 */
    public List<DataSource> listByType(String sourceType) {
        return sources.values().stream()
                .filter(ds -> ds.getSourceType().equalsIgnoreCase(sourceType))
                .filter(DataSource::isHealthy)
                .toList();
    }

    /** 列出所有已注册数据源 */
    public List<DataSource> listAll() {
        return List.copyOf(sources.values());
    }

    /** 列出所有可用的 */
    public List<DataSource> listAvailable() {
        return sources.values().stream()
                .filter(DataSource::isHealthy)
                .toList();
    }

    public int size() { return sources.size(); }
    public boolean isEmpty() { return sources.isEmpty(); }
}
