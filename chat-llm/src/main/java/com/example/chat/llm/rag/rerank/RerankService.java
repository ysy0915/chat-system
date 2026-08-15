package com.example.chat.llm.rag.rerank;

import com.example.chat.llm.rag.legacy.VectorStoreLegacy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <h2>语义重排服务（Rerank）</h2>
 *
 * <p>标准 RAG 精排层：对召回候选（向量 + 关键词融合后）按 query 相关性做<b>语义重排</b>，
 * 弥补"向量相似 ≠ 相关"与关键词误配的问题，精排后只把最相关的 topK 注入 LLM。</p>
 *
 * <p>支持两种 provider（{@code app.rag.rerank.provider}）：</p>
 * <ul>
 *   <li><b>jina</b>（默认）：Jina Reranker API（https://api.jina.ai/v1/rerank，
 *       model 默认 jina-reranker-v2-base-multilingual），需 {@code app.rag.rerank.api-key}</li>
 *   <li><b>local</b>：自部署 OpenAI 兼容 rerank 端点（如 bge-reranker 的 vLLM 服务），
 *       base-url 指向 /rerank，model 指定本地模型名</li>
 * </ul>
 *
 * <p>provider=none 或调用失败时<b>降级返回原序</b>，绝不阻塞主检索链路。</p>
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.rag.rerank.provider:none}")
    private String provider;

    @Value("${app.rag.rerank.base-url:https://api.jina.ai/v1/rerank}")
    private String baseUrl;

    @Value("${app.rag.rerank.model:jina-reranker-v2-base-multilingual}")
    private String model;

    @Value("${app.rag.rerank.api-key:}")
    private String apiKey;

    @Value("${app.rag.rerank.timeout-seconds:10}")
    private int timeoutSeconds;

    /**
     * 对候选列表做语义重排，返回按相关性降序的前 topK 条（relevance_score 归一化 0~1）。
     * provider=none / 未配置 key / 调用异常 → 返回原序截断。
     */
    public List<VectorStoreLegacy.SearchResult> rerank(String query,
                                                       List<VectorStoreLegacy.SearchResult> candidates,
                                                       int topK) {
        if ("none".equalsIgnoreCase(provider) || candidates == null || candidates.isEmpty()) {
            return truncate(candidates, topK);
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Rerank] provider={} 未配置 api-key，降级返回原序", provider);
            return truncate(candidates, topK);
        }
        try {
            List<String> docs = new ArrayList<>(candidates.size());
            for (VectorStoreLegacy.SearchResult c : candidates) {
                docs.add(c.text);
            }
            JsonNode body = objectMapper.createObjectNode()
                    .put("model", model)
                    .put("query", query)
                    .put("top_n", Math.max(topK, 1));
            body = ((com.fasterxml.jackson.databind.node.ObjectNode) body).set("documents", objectMapper.valueToTree(docs));

            JsonNode resp = post(baseUrl, body);
            JsonNode results = resp.path("results");
            if (!results.isArray()) {
                log.warn("[Rerank] 响应无 results，降级返回原序");
                return truncate(candidates, topK);
            }
            // 按 relevance_score 降序排序
            List<Reranked> ranked = new ArrayList<>();
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < candidates.size()) {
                    ranked.add(new Reranked(index, (float) score));
                }
            }
            ranked.sort(Comparator.comparingDouble((Reranked r) -> r.score).reversed());

            List<VectorStoreLegacy.SearchResult> out = new ArrayList<>(topK);
            for (Reranked r : ranked) {
                if (out.size() >= topK) {
                    break;
                }
                VectorStoreLegacy.SearchResult c = candidates.get(r.index);
                out.add(new VectorStoreLegacy.SearchResult(c.text, c.source, c.docId, r.score, c.page));
            }
            log.info("[Rerank] provider={} 候选 {} → 精排 {}", provider, candidates.size(), out.size());
            return out;
        } catch (Exception e) {
            log.warn("[Rerank] 重排失败（{}），降级返回原序: {}", provider, e.getMessage());
            return truncate(candidates, topK);
        }
    }

    private List<VectorStoreLegacy.SearchResult> truncate(List<VectorStoreLegacy.SearchResult> list, int topK) {
        if (list == null || list.isEmpty()) {
            return list == null ? List.of() : list;
        }
        return list.size() > topK ? list.subList(0, topK) : list;
    }

    /** POST JSON 到 rerank 端点，返回响应 JSON；非 2xx 抛异常 */
    private JsonNode post(String url, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                        StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body() == null ? "{}" : response.body());
    }

    /** 候选下标 + 重排分数 */
    private record Reranked(int index, float score) {}
}
