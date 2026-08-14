package com.example.chat.intent.funnel;

import com.example.chat.exception.LLMCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图语义匹配专用 Embedding 服务（intent_examples 集合为 text-embedding-v3 1024 维）。
 *
 * <p>与 RAG 知识库解耦：RAG 运行时已迁移至 chat-llm（新版 embedding 维度不同），
 * 为避免 intent_examples 集合维度不兼容，意图匹配保留本地 DashScope 向量化。
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class IntentEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(IntentEmbeddingService.class);

    /** api-mode: dashscope (原生) | openai-compat (兼容 OpenAI) */
    @Value("${app.rag.embedding.mode:dashscope}")
    private String apiMode;

    @Value("${app.rag.embedding.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String baseUrl;

    @Value("${app.rag.embedding.api-key:}")
    private String apiKey;

    @Value("${app.rag.embedding.model:text-embedding-v3}")
    private String model;

    /** 向量维度（text-embedding-v3 默认 1024） */
    @Value("${app.rag.embedding.dimension:1024}")
    private int dimension;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public IntentEmbeddingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        log.info("[IntentEmbedding] 初始化 apiMode={} model={} dim={}", apiMode, model, dimension);
    }

    /**
     * 将单条文本转为向量
     * @param text 输入文本（建议 < 2048 token）
     * @return float[] 向量，维度由 dimension 决定
     */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 批量文本转向量（减少 API 调用次数，单次最多 10 条）
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            final String url;
            final String bodyJson;

            if ("openai-compat".equalsIgnoreCase(apiMode)) {
                // OpenAI 兼容模式
                url = baseUrl.replaceAll("/+$", "") + "/embeddings";
                LinkedHashMap<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("input", texts);
                bodyJson = objectMapper.writeValueAsString(body);
            } else {
                // DashScope 原生 API（默认）
                url = baseUrl.replaceAll("/+$", "")
                        + "/services/embeddings/text-embedding/text-embedding";
                LinkedHashMap<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                LinkedHashMap<String, Object> input = new LinkedHashMap<>();
                input.put("texts", texts);
                body.put("input", input);
                LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("dimension", dimension);
                parameters.put("output_type", "dense");
                body.put("parameters", parameters);
                bodyJson = objectMapper.writeValueAsString(body);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[IntentEmbedding] status={} url={} body={}", response.statusCode(), url,
                        response.body().length() > 500 ? response.body().substring(0, 500) : response.body());
                throw new LLMCallException(response.statusCode(),
                        "Embedding API 失败 status=" + response.statusCode());
            }

            return parseResponse(response.body());

        } catch (LLMCallException le) {
            throw le;
        } catch (Exception e) {
            // 其余异常包装为 LLMCallException
            log.error("[IntentEmbedding] 转向量失败 model={} error={}", model, e.getMessage());
            throw new LLMCallException("Embedding 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<float[]> parseResponse(String body) throws Exception {
        Map<String, Object> result = objectMapper.readValue(body, Map.class);

        if ("openai-compat".equalsIgnoreCase(apiMode)) {
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            return data.stream()
                    .map(item -> toFloatArray((List<Number>) item.get("embedding")))
                    .toList();
        } else {
            // DashScope 原生响应格式
            Map<String, Object> output = (Map<String, Object>) result.get("output");
            List<Map<String, Object>> embeddings =
                    (List<Map<String, Object>>) output.get("embeddings");
            return embeddings.stream()
                    .map(item -> toFloatArray((List<Number>) item.get("embedding")))
                    .toList();
        }
    }

    private float[] toFloatArray(List<Number> vector) {
        float[] arr = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            arr[i] = vector.get(i).floatValue();
        }
        return arr;
    }

    public int getDimension() {
        return dimension;
    }
}
