package com.example.chat.storage;

/**
 * <h2>存储 SPI 顶层接口（存储热插拔契约）</h2>
 *
 * <p>[B档] 存储平台化：所有可被业务使用的存储（向量库 / 图数据库 / KV 缓存 / 对象存储…）
 * 统一实现本接口，由 {@link StorageRegistry} 按 {@code type()} 自动收集注册。
 * 新增一类存储 = 新增一个实现类并标注 Spring 组件（或调用 registry.register），
 * <b>无需改动任何注册中心 / 业务代码</b>，实现存储热插拔。</p>
 *
 * <p>与 {@code LLMProviderStrategyFactory}（LLM 厂商 SPI）同范式：
 * Spring Bean 自动收集 + 动态注册 + 未知类型容错。</p>
 */
public interface Storage {

    /**
     * 存储大类类型标识（小写，全局唯一）：vector / graph / kv / object ...
     * 对应 {@link StorageRegistry#get(String)} 的查找键。
     */
    String type();

    /**
     * 存储实例名（小写）：milvus / neo4j / redis / oss ...
     * 同类型下可有多个实例（如 milvus 主备）。
     */
    String name();

    /**
     * 是否可用（连接是否建立成功）。注册中心 {@code status()} 汇总上报 actuator。
     */
    default boolean isAvailable() {
        return true;
    }

    /** 人类可读描述，用于管理面展示 */
    default String description() {
        return type() + ":" + name();
    }
}
