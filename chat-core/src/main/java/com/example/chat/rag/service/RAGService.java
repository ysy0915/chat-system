package com.example.chat.rag.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG（检索增强生成）核心服务
 *
 * 流程：
 *   用户提问 → 向量检索相关文档 → 拼接为 context → 调 LLM 生成回答
 *
 * 这是 Harness 层的核心组件，位于 LLMInvoker 之上：
 *   业务层 → RAGService → LLMInvoker → Strategy → 实际 LLM
 *
 * 业务层使用方式：
 *   // 不启用 RAG（原有行为）
 *   llmInvoker.invoke(config, messages, ...);
 *
 *   // 启用 RAG（自动检索知识库拼到 prompt）
 *   ragService.invokeWithRAG(config, knowledgeBaseId, userQuestion, messages, ...);
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class RAGService {

    private static final Logger log = LoggerFactory.getLogger(RAGService.class);

    @Autowired(required = false)
    private VectorStoreService vectorStoreService;

    @Autowired(required = false)
    private LLMInvoker llmInvoker;

    /** 检索的 topK 数量（默认 5 条最相关分片） */
    @Value("${app.rag.search.top-k:5}")
    private int topK;

    /** 相似度阈值（低于此分数的分片不纳入 context） */
    @Value("${app.rag.search.score-threshold:0.3}")
    private float scoreThreshold;

    /** context 最大字符数（防止 prompt 过长） */
    @Value("${app.rag.context.max-chars:3000}")
    private int maxContextChars;

    /**
     * RAG 检索 + LLM 调用（非流式）
     *
     * @param config          模型配置
     * @param knowledgeBaseId 知识库 ID（null 则不检索，直接调 LLM）
     * @param userQuestion    用户原始问题（用于检索）
     * @param messages        对话历史（RAG 会把检索结果作为 system 消息插入最前面）
     * @param temperature     温度
     * @param scene           调用场景
     * @param defaultBaseUrl  默认 baseUrl
     * @param defaultApiKey   默认 API Key
     */
    public String invokeWithRAG(ModelConfig config, Long knowledgeBaseId,
                                 String userQuestion, List<LLMMessage> messages,
                                 double temperature, String scene,
                                 String defaultBaseUrl, String defaultApiKey) throws Exception {
        // 1. 检索相关文档
        String context = retrieveContext(knowledgeBaseId, userQuestion);

        // 2. 把 context 插入 messages 最前面（作为 system 消息）
        List<LLMMessage> ragMessages = new ArrayList<>(messages);
        if (context != null && !context.isBlank()) {
            String systemPrompt = buildRAGSystemPrompt(context);
            ragMessages.add(0, LLMMessage.system(systemPrompt));
            log.info("[RAG] kb={} 检索到 context，拼入 prompt，长度={}", knowledgeBaseId, context.length());
        } else {
            log.info("[RAG] kb={} 无相关文档，直接调用 LLM", knowledgeBaseId);
        }

        // 3. 调用 LLM
        return llmInvoker.invoke(config, ragMessages, temperature, scene + ":rag", defaultBaseUrl, defaultApiKey);
    }

    /**
     * RAG 检索 + LLM 流式调用
     *
     * @param callback token 回调（同 LLMInvoker.invokeStream 的 callback）
     */
    public String invokeWithRAGStream(ModelConfig config, Long knowledgeBaseId,
                                      String userQuestion, List<LLMMessage> messages,
                                      double temperature, String scene,
                                      String defaultBaseUrl, String defaultApiKey,
                                      java.util.function.Consumer<String> callback) throws Exception {
        String context = retrieveContext(knowledgeBaseId, userQuestion);

        List<LLMMessage> ragMessages = new ArrayList<>(messages);
        if (context != null && !context.isBlank()) {
            String systemPrompt = buildRAGSystemPrompt(context);
            ragMessages.add(0, LLMMessage.system(systemPrompt));
        }

        return llmInvoker.invokeStream(config, ragMessages, temperature, scene + ":rag",
                defaultBaseUrl, defaultApiKey, callback);
    }

    /**
     * 检索知识库，返回拼好的 context 字符串
     */
    public String retrieveContext(Long knowledgeBaseId, String query) {
        if (knowledgeBaseId == null || vectorStoreService == null) {
            return null;
        }

        List<VectorStoreService.SearchResult> results =
                vectorStoreService.search(knowledgeBaseId, query, topK);

        if (results.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        for (VectorStoreService.SearchResult r : results) {
            if (r.score < scoreThreshold) continue;
            if (totalChars + r.text.length() > maxContextChars) break;

            sb.append("--- 来源: ").append(r.source).append(" (相似度: ")
              .append(String.format("%.2f", r.score)).append(") ---\n");
            sb.append(r.text).append("\n\n");
            totalChars += r.text.length();
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 构建 RAG 的 system prompt
     */
    private String buildRAGSystemPrompt(String context) {
        return "你是一个知识库问答助手。请根据以下检索到的参考资料回答用户问题。\n" +
               "如果参考资料中没有相关信息，请坦诚告知，不要编造。\n" +
               "回答时可以引用资料来源。\n\n" +
               "【参考资料】\n" + context;
    }
}
