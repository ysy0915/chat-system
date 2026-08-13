package com.example.chat.intent.funnel;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 意图语义匹配专用 Milvus 连接配置。
 *
 * <p>仅服务于 ContextMatcher 的 intent_examples 集合（chat-core 私有意图语义匹配，
 * 不属于 RAG 知识库功能）。RAG 运行时的 Milvus 连接已迁移至 chat-llm。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true", matchIfMissing = false)
public class IntentMilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(IntentMilvusConfig.class);

    @Value("${app.rag.milvus.host:127.0.0.1}")
    private String host;

    @Value("${app.rag.milvus.port:19530}")
    private int port;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        log.info("[IntentMilvus] Milvus 连接 {}:{}（意图语义匹配）", host, port);
        return new MilvusServiceClient(connectParam);
    }
}
