package com.example.chat.rag.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 模块总配置
 * 通过 app.rag.enabled=true 开启整个 RAG 功能
 * 开启后自动注入 MilvusConfig、EmbeddingService、VectorStoreService、RAGService
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true", matchIfMissing = false)
public class RAGAutoConfiguration {
    // 配置类，组件通过 @Component + @ConditionalOnProperty 自动注册
}
