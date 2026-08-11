package com.example.chat.llm.rag.config;

import com.example.chat.llm.rag.EmbeddingService;
import com.example.chat.llm.rag.datasource.DataSource;
import com.example.chat.llm.rag.datasource.DataSourceRegistry;
import com.example.chat.llm.rag.store.StoreType;
import com.example.chat.llm.rag.store.VectorStoreAdapter;
import com.example.chat.llm.rag.store.VectorStoreConfig;
import com.example.chat.llm.rag.store.VectorStoreRegistry;
import com.example.chat.llm.rag.store.milvus.MilvusVectorStoreAdapter;
import com.example.chat.llm.rag.store.pinecone.PineconeVectorStoreAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <h2>RAG 自动装配</h2>
 *
 * <p>根据 rag.store-type 创建向量库适配器，并组装默认 DataSource 注册到 DataSourceRegistry。
 *
 * <h3>多数据源扩展</h3>
 * 启动后可通过 DataSourceRegistry.register() 动态追加 dataSource（如从 DB 加载）。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class VectorStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreAutoConfiguration.class);

    /**
     * 创建并注册默认向量库适配器。
     */
    @Bean(destroyMethod = "close")
    public VectorStoreAdapter vectorStoreAdapter(RagProperties props, VectorStoreRegistry storeRegistry) {
        VectorStoreConfig config = buildStoreConfig(props);
        VectorStoreAdapter adapter = createAdapter(props.getStoreType());
        storeRegistry.register(adapter, config);
        log.info("[VectorStore] 默认向量库已就绪 type={} name={}", props.getStoreType(), props.getStoreName());
        return adapter;
    }

    /**
     * 组装默认 DataSource 并注册到 DataSourceRegistry。
     * DataSource 捆绑了: 向量库 + Embedding + LLM 生成模型。
     */
    @Bean
    public DataSource defaultDataSource(RagProperties props,
                                        VectorStoreAdapter storeAdapter,
                                        EmbeddingService embeddingService,
                                        DataSourceRegistry dsRegistry) {

        DataSource ds = DataSource.builder()
                .name("default")
                .displayName("默认知识库")
                .sourceType("RAG")
                .enabled(true)
                .isDefault(true)
                .priority(0)
                .store(storeAdapter)
                .embeddingService(embeddingService)
                .embeddingProvider("qwen")
                .embeddingModel(props.getEmbedding().getModel())
                .genProvider(props.getGenProvider())
                .genModel(props.getGenModel())
                .genMaxTokens(props.getGenMaxTokens())
                .topK(props.getDefaultTopK())
                .scoreThreshold(props.getDefaultScoreThreshold())
                .chunkSize(props.getDefaultChunkSize())
                .chunkOverlap(props.getDefaultChunkOverlap())
                .description("默认 RAG 数据源 (YAML 配置)")
                .build();

        dsRegistry.register(ds);
        log.info("[DataSource] 默认数据源已注册: {}", ds);
        return ds;
    }

    // ── 内部方法 ──────────────────────────────────────────

    private VectorStoreAdapter createAdapter(StoreType type) {
        return switch (type) {
            case MILVUS        -> new MilvusVectorStoreAdapter();
            case PINECONE      -> new PineconeVectorStoreAdapter();
            case WEAVIATE, QDRANT, ELASTICSEARCH, CHROMA
                -> throw new UnsupportedOperationException(
                        "向量库类型 " + type + " 暂未实现，欢迎扩展 VectorStoreAdapter 接口！");
        };
    }

    private VectorStoreConfig buildStoreConfig(RagProperties props) {
        return VectorStoreConfig.builder()
                .storeType(props.getStoreType())
                .name(props.getStoreName())
                .host(props.getStoreHost())
                .port(props.getStorePort())
                .databaseName(props.getStoreDatabase())
                .collectionName(props.getStoreCollection())
                .dimension(props.getStoreDimension())
                .enabled(true)
                .isDefault(true)
                .extraProps(props.getStoreProps())
                .build();
    }
}
