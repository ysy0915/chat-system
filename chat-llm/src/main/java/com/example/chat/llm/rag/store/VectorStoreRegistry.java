package com.example.chat.llm.rag.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * <h2>向量库注册中心</h2>
 *
 * 管理所有 {@link VectorStoreAdapter} 实现，提供：
 * <ul>
 *   <li>适配器注册/移除</li>
 *   <li>按 {@link StoreType} 查找默认适配器</li>
 *   <li>列出所有可用/健康的适配器</li>
 * </ul>
 *
 * <p>使用时只需注入 Registry，无需关心底层是 Milvus 还是 Pinecone。</p>
 */
@Component
public class VectorStoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreRegistry.class);

    /**
     * 已注册的适配器列表（支持同一类型注册多个实例，以 name 区分）
     */
    private final Map<String, VectorStoreAdapter> adaptersByName = new ConcurrentHashMap<>();

    /**
     * 各类型的默认适配器
     */
    private final Map<StoreType, VectorStoreAdapter> defaultByType = new ConcurrentHashMap<>();

    /**
     * 所有适配器的有序列表（保留注册顺序）
     */
    private final List<VectorStoreAdapter> allAdapters = new CopyOnWriteArrayList<>();

    // ── 注册 ──────────────────────────────────────────────

    public void register(VectorStoreAdapter adapter, VectorStoreConfig config) {
        String name = config.getName();
        adapter.init(config);
        adaptersByName.put(name, adapter);
        allAdapters.add(adapter);
        if (config.isDefault()) {
            defaultByType.put(adapter.getStoreType(), adapter);
        }
        log.info("[VectorStoreRegistry] 注册向量库: type={} name={} host={}:{} default={}",
                adapter.getStoreType(), name, config.getHost(), config.getPort(), config.isDefault());
    }

    public void unregister(String name) {
        VectorStoreAdapter adapter = adaptersByName.remove(name);
        if (adapter != null) {
            allAdapters.remove(adapter);
            defaultByType.values().remove(adapter);
            try { adapter.close(); } catch (Exception ignore) { }
            log.info("[VectorStoreRegistry] 移除向量库: name={}", name);
        }
    }

    // ── 查找 ──────────────────────────────────────────────

    /** 按名称查找 */
    public VectorStoreAdapter getByName(String name) {
        return adaptersByName.get(name);
    }

    /** 获取指定类型的默认适配器 */
    public VectorStoreAdapter getDefault(StoreType type) {
        VectorStoreAdapter adapter = defaultByType.get(type);
        if (adapter != null && adapter.isHealthy()) {
            return adapter;
        }
        // fallback: 找第一个同类型且健康的
        return allAdapters.stream()
                .filter(a -> a.getStoreType() == type && a.isHealthy())
                .findFirst().orElse(null);
    }

    /** 获取第一个可用且健康的默认适配器（跨类型） */
    public VectorStoreAdapter getDefaultOrFirst() {
        // 优先返回任意类型的默认
        return defaultByType.values().stream()
                .filter(VectorStoreAdapter::isHealthy)
                .findFirst()
                .or(() -> allAdapters.stream().filter(VectorStoreAdapter::isHealthy).findFirst())
                .orElseThrow(() -> new IllegalStateException("没有可用的向量库适配器"));
    }

    /** 列出所有适配器 */
    public List<VectorStoreAdapter> listAll() {
        return List.copyOf(allAdapters);
    }

    /** 列出所有健康的适配器 */
    public List<VectorStoreAdapter> listHealthy() {
        return allAdapters.stream().filter(VectorStoreAdapter::isHealthy).collect(Collectors.toList());
    }

    /** 获取所有已注册类型 */
    public List<StoreType> getRegisteredTypes() {
        return allAdapters.stream().map(VectorStoreAdapter::getStoreType).distinct().collect(Collectors.toList());
    }
}
