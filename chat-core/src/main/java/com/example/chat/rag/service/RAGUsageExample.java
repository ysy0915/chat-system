package com.example.chat.rag.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 业务接入示例
 *
 * 演示如何在 TreeHoleService / ChatProcessor 等业务类中接入 RAG
 * 实际项目中可根据需要把 RAG 逻辑直接写到对应 Service 里
 *
 * 使用方式（在业务类中）：
 *
 *   @Autowired(required = false)
 *   private RAGService ragService;
 *
 *   // 不启用 RAG（原有行为）
 *   String answer = llmInvoker.invoke(config, messages, 0.85, "treehole", defaultBaseUrl, defaultApiKey);
 *
 *   // 启用 RAG（传入知识库 ID）
 *   Long kbId = 1L;  // 从配置或数据库读取
 *   String answer = ragService.invokeWithRAG(config, kbId, userQuestion, messages,
 *           0.85, "treehole", defaultBaseUrl, defaultApiKey);
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class RAGUsageExample {

    private static final Logger log = LoggerFactory.getLogger(RAGUsageExample.class);

    @Autowired(required = false)
    private RAGService ragService;

    @Autowired(required = false)
    private com.example.chat.service.LLMInvoker llmInvoker;

    @Value("${app.rag.treehole.kb-id:1}")
    private Long treeholeKbId;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 示例：情绪树洞接入 RAG
     * 当 app.rag.enabled=true 且 ragService 注入成功时，自动启用知识库增强
     * 否则降级为普通 LLM 调用
     */
    public String treeHoleAskWithRAG(ModelConfig config, String userQuestion,
                                      List<LLMMessage> messages,
                                      String defaultBaseUrl, String defaultApiKey) throws Exception {

        if (ragService != null) {
            // RAG 模式：先检索知识库，再调 LLM
            log.info("[RAG-TreeHole] 启用知识库增强 kbId={}", treeholeKbId);
            return ragService.invokeWithRAG(config, treeholeKbId, userQuestion, messages,
                    0.85, "treehole", defaultBaseUrl, defaultApiKey);
        } else {
            // 降级：直接调 LLM
            return llmInvoker.invoke(config, messages, 0.85, "treehole", defaultBaseUrl, defaultApiKey);
        }
    }

    /**
     * 示例：流式调用接入 RAG
     */
    public String treeHoleAskWithRAGStream(ModelConfig config, String userQuestion,
                                            List<LLMMessage> messages,
                                            String defaultBaseUrl, String defaultApiKey,
                                            java.util.function.Consumer<String> callback) throws Exception {

        if (ragService != null) {
            return ragService.invokeWithRAGStream(config, treeholeKbId, userQuestion, messages,
                    0.85, "treehole", defaultBaseUrl, defaultApiKey, callback);
        } else {
            return llmInvoker.invokeStream(config, messages, 0.85, "treehole",
                    defaultBaseUrl, defaultApiKey, callback);
        }
    }
}
