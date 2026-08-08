package com.example.chat.rag.service;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding 服务：将文本转为向量
 * 默认使用阿里云 DashScope text-embedding-v3（1024 维）
 * 可通过 app.rag.embedding.provider 切换：dashscope / ollama / custom
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${app.rag.embedding.provider:dashscope}")
    private String provider;

    @Value("${app.rag.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${app.rag.embedding.api-key:}")
    private String apiKey;

    @Value("${app.rag.embedding.model:text-embedding-v3}")
    private String model;

    /** 向量维度（text-embedding-v3 默认 1024，bge-m3 为 1024，ollama nomic 为 768） */
    @Value("${app.rag.embedding.dimension:1024}")
    private int dimension;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 将单条文本转为向量
     * @param text 输入文本（建议 < 2048 token）
     * @return float[] 向量，维度由 dimension 决定
     */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 批量文本转向量（减少 API 调用次数，单次最多 25 条）
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/embeddings";

            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", texts);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Embedding API 失败 status=" + response.statusCode() + " body=" + response.body());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");

            return data.stream()
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        List<Number> vector = (List<Number>) item.get("embedding");
                        float[] arr = new float[vector.size()];
                        for (int i = 0; i < vector.size(); i++) {
                            arr[i] = vector.get(i).floatValue();
                        }
                        return arr;
                    })
                    .toList();
        } catch (Exception e) {
            log.error("[Embedding] 转向量失败 provider={} model={} error={}", provider, model, e.getMessage());
            throw new RuntimeException("Embedding 失败: " + e.getMessage(), e);
        }
    }

    public int getDimension() {
        return dimension;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }
}
