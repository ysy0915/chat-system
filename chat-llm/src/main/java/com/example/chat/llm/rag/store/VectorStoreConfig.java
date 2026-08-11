package com.example.chat.llm.rag.store;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 向量库通用配置模型 — 与 llm_vector_store_config + llm_vector_store_props 两张表对应。
 * <ul>
 *   <li>通用属性直接作为字段</li>
 *   <li>厂商特有属性存入 {@code extraProps} KV map</li>
 * </ul>
 */
public class VectorStoreConfig {

    // ── 通用属性 (llm_vector_store_config) ──────────────
    private Long   id;
    private StoreType storeType;
    private String name;
    private String host;
    private int    port;
    private String databaseName;
    private String collectionName;
    private int    dimension;
    private String authType;
    private boolean enabled;
    private boolean isDefault;
    private String description;

    // ── 厂商特殊属性 (llm_vector_store_props KV) ────────
    private Map<String, String> extraProps = new HashMap<>();

    // ── 便捷读取 ─────────────────────────────────────────
    public String getProp(String key)                 { return extraProps.get(key); }
    public String getProp(String key, String defVal)  { return extraProps.getOrDefault(key, defVal); }
    public int    getPropInt(String key, int defVal)  { try { return Integer.parseInt(extraProps.get(key)); } catch (Exception e) { return defVal; } }
    public boolean getPropBool(String key, boolean d) { return Boolean.parseBoolean(extraProps.getOrDefault(key, String.valueOf(d))); }

    // ── Builder ─────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final VectorStoreConfig cfg = new VectorStoreConfig();
        public Builder id(Long v)               { cfg.id = v;           return this; }
        public Builder storeType(StoreType v)   { cfg.storeType = v;    return this; }
        public Builder name(String v)           { cfg.name = v;         return this; }
        public Builder host(String v)           { cfg.host = v;         return this; }
        public Builder port(int v)              { cfg.port = v;         return this; }
        public Builder databaseName(String v)   { cfg.databaseName = v; return this; }
        public Builder collectionName(String v) { cfg.collectionName = v;return this; }
        public Builder dimension(int v)         { cfg.dimension = v;    return this; }
        public Builder authType(String v)       { cfg.authType = v;     return this; }
        public Builder enabled(boolean v)       { cfg.enabled = v;      return this; }
        public Builder isDefault(boolean v)     { cfg.isDefault = v;    return this; }
        public Builder description(String v)    { cfg.description = v;  return this; }
        public Builder extraProp(String k, String v) { cfg.extraProps.put(k, v); return this; }
        public Builder extraProps(Map<String, String> m) { cfg.extraProps.putAll(m); return this; }
        public VectorStoreConfig build() { return cfg; }
    }

    // ── Getters ──────────────────────────────────────────
    public Long getId()               { return id; }
    public StoreType getStoreType()   { return storeType; }
    public String getName()           { return name; }
    public String getHost()           { return host; }
    public int getPort()              { return port; }
    public String getDatabaseName()   { return databaseName; }
    public String getCollectionName() { return collectionName; }
    public int getDimension()         { return dimension; }
    public String getAuthType()       { return authType; }
    public boolean isEnabled()        { return enabled; }
    public boolean isDefault()        { return isDefault; }
    public String getDescription()    { return description; }
    public Map<String, String> getExtraProps() { return Collections.unmodifiableMap(extraProps); }

    @Override
    public String toString() {
        return "VectorStoreConfig{type=" + storeType + ", name='" + name + "', host='" + host + ":" + port + "'}";
    }
}
