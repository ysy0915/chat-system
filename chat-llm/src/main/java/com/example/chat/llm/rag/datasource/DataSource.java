package com.example.chat.llm.rag.datasource;

import com.example.chat.llm.rag.store.VectorStoreAdapter;
import com.example.chat.llm.rag.EmbeddingService;

/**
 * <h2>RAG 数据源 — 统一抽象</h2>
 *
 * 一个 DataSource 捆绑了完成一次 RAG 调用所需的所有组件:
 * <ol>
 *   <li><b>向量库适配器</b> — 存储和检索向量</li>
 *   <li><b>Embedding 服务</b> — 文本向量化（可不同模型）</li>
 *   <li><b>LLM 生成路由</b> — provider + model（用于最终回答）</li>
 *   <li><b>检索参数</b> — top-K、阈值、分块配置</li>
 * </ol>
 *
 * <h3>多数据源切换</h3>
 * 调用方只需指定 dataSourceName，DataSourceRegistry 自动解析完整路由。
 */
public class DataSource {

    private String  name;
    private String  displayName;
    private String  sourceType;        // RAG / AGENT_RAG / MULTI_MODAL_RAG
    private boolean enabled;
    private boolean isDefault;
    private int     priority;

    // ── 向量库 ─────────────────
    private VectorStoreAdapter store;

    // ── Embedding ─────────────
    private EmbeddingService embeddingService;
    private String           embeddingProvider;
    private String           embeddingModel;

    // ── LLM 生成 ──────────────
    private String genProvider;
    private String genModel;
    private int    genMaxTokens;

    // ── 检索参数 ──────────────
    private int   topK            = 5;
    private float scoreThreshold  = 0.5f;
    private int   chunkSize       = 500;
    private int   chunkOverlap    = 50;

    private String description;

    // ── 快捷方法 ────────────────────────────────────────

    public boolean isHealthy() {
        return enabled && store != null && store.isHealthy() && embeddingService != null;
    }

    // ── Getters / Setters ───────────────────────────────

    public String getName()              { return name; }
    public void   setName(String n)      { this.name = n; }
    public String getDisplayName()       { return displayName; }
    public void   setDisplayName(String n){ this.displayName = n; }
    public String getSourceType()        { return sourceType; }
    public void   setSourceType(String t){ this.sourceType = t; }
    public boolean isEnabled()           { return enabled; }
    public void   setEnabled(boolean e)  { this.enabled = e; }
    public boolean isDefault()           { return isDefault; }
    public void   setDefault(boolean d)  { this.isDefault = d; }
    public int    getPriority()          { return priority; }
    public void   setPriority(int p)     { this.priority = p; }

    public VectorStoreAdapter getStore()           { return store; }
    public void   setStore(VectorStoreAdapter s)   { this.store = s; }

    public EmbeddingService getEmbeddingService()       { return embeddingService; }
    public void   setEmbeddingService(EmbeddingService s){ this.embeddingService = s; }
    public String getEmbeddingProvider()                 { return embeddingProvider; }
    public void   setEmbeddingProvider(String p)         { this.embeddingProvider = p; }
    public String getEmbeddingModel()                    { return embeddingModel; }
    public void   setEmbeddingModel(String m)            { this.embeddingModel = m; }

    public String getGenProvider()       { return genProvider; }
    public void   setGenProvider(String p){ this.genProvider = p; }
    public String getGenModel()          { return genModel; }
    public void   setGenModel(String m)  { this.genModel = m; }
    public int    getGenMaxTokens()      { return genMaxTokens; }
    public void   setGenMaxTokens(int t) { this.genMaxTokens = t; }

    public int    getTopK()              { return topK; }
    public void   setTopK(int k)         { this.topK = k; }
    public float  getScoreThreshold()    { return scoreThreshold; }
    public void   setScoreThreshold(float t){ this.scoreThreshold = t; }
    public int    getChunkSize()         { return chunkSize; }
    public void   setChunkSize(int s)    { this.chunkSize = s; }
    public int    getChunkOverlap()      { return chunkOverlap; }
    public void   setChunkOverlap(int o) { this.chunkOverlap = o; }

    public String getDescription()       { return description; }
    public void   setDescription(String d){ this.description = d; }

    @Override public String toString() {
        return "DataSource{" + name + " type=" + sourceType +
               " store=" + (store != null ? store.getStoreType() : "null") +
               " embedding=" + embeddingProvider + "/" + embeddingModel +
               " gen=" + genProvider + "/" + genModel + "}";
    }

    // ── Builder ──────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final DataSource ds = new DataSource();
        public Builder name(String v)              { ds.name = v;         return this; }
        public Builder displayName(String v)       { ds.displayName = v;  return this; }
        public Builder sourceType(String v)        { ds.sourceType = v;   return this; }
        public Builder enabled(boolean v)          { ds.enabled = v;      return this; }
        public Builder isDefault(boolean v)        { ds.isDefault = v;    return this; }
        public Builder priority(int v)             { ds.priority = v;     return this; }
        public Builder store(VectorStoreAdapter v) { ds.store = v;        return this; }
        public Builder embeddingService(EmbeddingService v) { ds.embeddingService = v; return this; }
        public Builder embeddingProvider(String v) { ds.embeddingProvider = v; return this; }
        public Builder embeddingModel(String v)    { ds.embeddingModel = v;  return this; }
        public Builder genProvider(String v)       { ds.genProvider = v;     return this; }
        public Builder genModel(String v)          { ds.genModel = v;        return this; }
        public Builder genMaxTokens(int v)         { ds.genMaxTokens = v;    return this; }
        public Builder topK(int v)                 { ds.topK = v;            return this; }
        public Builder scoreThreshold(float v)     { ds.scoreThreshold = v;  return this; }
        public Builder chunkSize(int v)            { ds.chunkSize = v;       return this; }
        public Builder chunkOverlap(int v)         { ds.chunkOverlap = v;    return this; }
        public Builder description(String v)       { ds.description = v;     return this; }
        public DataSource build()                  { return ds; }
    }
}
