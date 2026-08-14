package com.example.chat.llm.rag.legacy;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 旧版 RAG 运行时（知识库 kbId 模型）配置 —— 提供 Milvus 直连客户端。
 *
 * <p>由 app.rag.enabled=true 开启；与 chat-core 旧版行为保持一致，
 * 但代码归属已在 chat-llm 模块（知识库管理 + 对话记忆 + 按 kbId 检索）。
 */
@Configuration
@ConditionalOnExpression("'${app.rag.enabled:false}' == 'true' and '${app.rag.backend:milvus}' == 'milvus'")
public class LegacyRagConfig {

    private static final Logger log = LoggerFactory.getLogger(LegacyRagConfig.class);

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
