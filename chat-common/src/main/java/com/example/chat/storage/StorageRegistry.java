package com.example.chat.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>存储注册中心 — 存储 SPI 热插拔核心</h2>
 *
 * <p>[B档] 存储平台化：Spring 启动时自动收集容器中所有 {@link Storage} 实现，
 * 按 {@code type()} 建立索引；新增一类存储（Milvus / Neo4j / Redis / OSS …）
 * 只需实现接口并标注 Spring 组件（或调用 {@link #register}），
 * <b>无需改动注册中心与业务代码</b>。</p>
 *
 * <p>与 {@code LLMProviderStrategyFactory}（LLM 厂商 SPI）同范式：
 * <ul>
 *   <li><b>自动收集</b>：构造器注入 {@code List<Storage>}（内置 + 自定义 SPI 全覆盖）</li>
 *   <li><b>动态注册</b>：{@link #register(Storage)}，同名 type 由后注册者覆盖</li>
 *   <li><b>容错</b>：未知 type 返回 null，调用方自行降级（业务链路不中断）</li>
 * </ul>
 * </p>
 */
@Component
public class StorageRegistry {

    private static final Logger log = LoggerFactory.getLogger(StorageRegistry.class);

    /** type(小写) → 存储实例 */
    private final Map<String, Storage> storages = new ConcurrentHashMap<>();

    /**
     * Spring 构造器：自动收集所有 {@link Storage} Bean（内置 + 第三方 SPI）。
     */
    @Autowired
    public StorageRegistry(List<Storage> spiStorages) {
        if (spiStorages != null) {
            for (Storage s : spiStorages) {
                register(s);
            }
        }
    }

    /**
     * 便捷构造（单元测试 / 非 Spring 环境）。
     */
    public StorageRegistry() {
    }

    /**
     * 动态注册存储实现（SPI 扩展点）；同名 type 覆盖旧实现。
     */
    public void register(Storage storage) {
        if (storage == null) {
            return;
        }
        String type = storage.type() == null ? "" : storage.type().trim().toLowerCase();
        if (type.isBlank()) {
            log.warn("[StorageRegistry] 忽略非法存储注册 type=null class={}",
                    storage.getClass().getSimpleName());
            return;
        }
        Storage old = storages.put(type, storage);
        log.info("[StorageRegistry] 注册存储 type={} impl={} (name={}){}",
                type, storage.getClass().getSimpleName(), storage.name(),
                old != null ? " 覆盖 " + old.getClass().getSimpleName() : "");
    }

    /**
     * 按 type 获取存储实例；未知类型返回 null（调用方降级）。
     */
    public Storage get(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return storages.get(type.trim().toLowerCase());
    }

    /**
     * 按 type 获取并强转类型；不匹配返回 null。
     */
    public <T extends Storage> T get(String type, Class<T> clazz) {
        Storage s = get(type);
        if (s == null) {
            return null;
        }
        return clazz.isInstance(s) ? clazz.cast(s) : null;
    }

    /**
     * 是否注册了至少一个存储。
     */
    public boolean has(String type) {
        return get(type) != null;
    }

    /**
     * 全部存储实例（按 type 排序）。
     */
    public List<Storage> all() {
        List<Storage> list = new ArrayList<>(storages.values());
        list.sort(Comparator.comparing(Storage::type));
        return list;
    }

    /**
     * 全部已注册 type 集合（管理面展示 / 配置校验）。
     */
    public List<String> supportedTypes() {
        List<String> types = new ArrayList<>(storages.keySet());
        Collections.sort(types);
        return types;
    }

    /**
     * 存储健康汇总（供管理面 / actuator 上报）：type → {name, available}。
     */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Storage> e : storages.entrySet()) {
            Storage s = e.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", s.name());
            item.put("description", s.description());
            item.put("available", s.isAvailable());
            result.put(e.getKey(), item);
        }
        return result;
    }
}
