package com.example.chat.llm.rag.config;

import com.example.chat.llm.rag.store.StoreType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 通用配置属性。
 *
 * <p>支持通过 YAML 直接配置向量库基础信息，也支持通过 DB 表动态切换。</p>
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private boolean enabled = false;

    /** 默认使用的向量库类型: MILVUS / PINECONE / WEAVIATE 等 */
    private StoreType storeType = StoreType.MILVUS;

    /** 默认使用的向量库名称（对应 llm_vector_store_config.name） */
    private String storeName = "milvus-default";

    // ── 快速 YAML 直连配置（不依赖 DB 表时使用）──
    private String storeHost      = "127.0.0.1";
    private int    storePort      = 19530;
    private String storeDatabase  = "default";
    private String storeCollection = "rag_documents";
    private int    storeDimension = 1536;

    /** 厂商特殊 KV 属性（key → value） */
    private Map<String, String> storeProps = new HashMap<>();

    /** Embedding 模型配置 */
    private Embedding embedding = new Embedding();

    /** 文档分块默认值 */
    private int defaultChunkSize    = 500;
    private int defaultChunkOverlap = 50;

    /** 生成模型默认值（可在 YAML 或环境变量覆盖） */
    private String genProvider  = "deepseek";
    private String genModel     = "deepseek-chat";
    private int    genMaxTokens = 4096;

    /** 检索默认参数 */
    private int   defaultTopK           = 5;
    private float defaultScoreThreshold = 0.5f;

    // ── Getters / Setters ───────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public StoreType getStoreType() { return storeType; }
    public void setStoreType(StoreType storeType) { this.storeType = storeType; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getStoreHost() { return storeHost; }
    public void setStoreHost(String storeHost) { this.storeHost = storeHost; }

    public int getStorePort() { return storePort; }
    public void setStorePort(int storePort) { this.storePort = storePort; }

    public String getStoreDatabase() { return storeDatabase; }
    public void setStoreDatabase(String storeDatabase) { this.storeDatabase = storeDatabase; }

    public String getStoreCollection() { return storeCollection; }
    public void setStoreCollection(String storeCollection) { this.storeCollection = storeCollection; }

    public int getStoreDimension() { return storeDimension; }
    public void setStoreDimension(int storeDimension) { this.storeDimension = storeDimension; }

    public Map<String, String> getStoreProps() { return storeProps; }
    public void setStoreProps(Map<String, String> storeProps) { this.storeProps = storeProps; }

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }

    public int getDefaultChunkSize() { return defaultChunkSize; }
    public void setDefaultChunkSize(int defaultChunkSize) { this.defaultChunkSize = defaultChunkSize; }

    public int getDefaultChunkOverlap() { return defaultChunkOverlap; }
    public void setDefaultChunkOverlap(int defaultChunkOverlap) { this.defaultChunkOverlap = defaultChunkOverlap; }

    public String getGenProvider() { return genProvider; }
    public void setGenProvider(String genProvider) { this.genProvider = genProvider; }

    public String getGenModel() { return genModel; }
    public void setGenModel(String genModel) { this.genModel = genModel; }

    public int getGenMaxTokens() { return genMaxTokens; }
    public void setGenMaxTokens(int genMaxTokens) { this.genMaxTokens = genMaxTokens; }

    public int getDefaultTopK() { return defaultTopK; }
    public void setDefaultTopK(int defaultTopK) { this.defaultTopK = defaultTopK; }

    public float getDefaultScoreThreshold() { return defaultScoreThreshold; }
    public void setDefaultScoreThreshold(float defaultScoreThreshold) { this.defaultScoreThreshold = defaultScoreThreshold; }

    // ── Embedding 模型配置 ──────────────────────────────

    public static class Embedding {
        private String model   = "text-embedding-3-small";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey  = "";

        public String getModel()   { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey()  { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
