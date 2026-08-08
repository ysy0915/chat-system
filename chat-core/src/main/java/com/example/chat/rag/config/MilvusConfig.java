package com.example.chat.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库连接配置
 * 通过 app.rag.milvus.enabled=true 开启
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.milvus.enabled", havingValue = "true", matchIfMissing = false)
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

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
        log.info("[RAG] Milvus 连接 {}:{}", host, port);
        return new MilvusServiceClient(connectParam);
    }
}
