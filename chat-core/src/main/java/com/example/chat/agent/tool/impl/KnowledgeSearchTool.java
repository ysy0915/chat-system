package com.example.chat.agent.tool.impl;

import com.example.chat.agent.tool.Tool;
import com.example.chat.client.RagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库搜索工具
 * 通过 RagClient 调用 chat-llm 的 /internal/rag/search 进行语义检索
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class KnowledgeSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    /** 默认检索的知识库 ID（可通过 app.agent.default-kb-id 配置） */
    @Value("${app.agent.default-kb-id:1}")
    private long defaultKbId;

    /** 检索返回的分片数 */
    @Value("${app.rag.search.top-k:5}")
    private int topK;

    private final RagClient ragClient;

    @Autowired
    public KnowledgeSearchTool(RagClient ragClient) {
        this.ragClient = ragClient;
    }

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        return "在知识库中进行语义检索。当用户的问题可能与已上传的文档/FAQ 知识库相关时调用此工具获取相关资料。";
    }

    @Override
    public String getParameters() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"搜索关键词或问题\"},"
                + "\"kb_id\":{\"type\":\"integer\",\"description\":\"知识库 ID，不传则使用默认知识库\"}"
                + "},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        Object queryObj = params.get("query");
        if (queryObj == null || queryObj.toString().isBlank()) {
            return "[缺少参数: query]";
        }
        String query = queryObj.toString().trim();

        long kbId = defaultKbId;
        Object kbIdObj = params.get("kb_id");
        if (kbIdObj != null) {
            try {
                kbId = Long.parseLong(kbIdObj.toString());
            } catch (NumberFormatException ignored) {
                // 用默认值
            }
        }

        try {
            List<RagClient.SearchResult> results = ragClient.search(kbId, query, topK);
            if (results == null || results.isEmpty()) {
                return "[未检索到相关内容，query=" + query + "]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("检索到 ").append(results.size()).append(" 条相关内容：\n");
            for (int i = 0; i < results.size(); i++) {
                RagClient.SearchResult r = results.get(i);
                sb.append("--- 结果 ").append(i + 1).append(" (相似度: ")
                  .append(String.format("%.3f", r.score())).append(", 来源: ").append(r.source()).append(") ---\n");
                sb.append(r.text()).append('\n');
            }
            log.info("[KnowledgeSearchTool] kb={} query=\"{}\" 命中 {}", kbId, query, results.size());
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[KnowledgeSearchTool] 检索失败 kb={} query={}: {}", kbId, query, e.getMessage());
            return "[知识库检索失败: " + e.getMessage() + "]";
        }
    }
}
