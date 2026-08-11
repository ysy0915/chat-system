package com.example.chat.llm.rag;

import com.example.chat.llm.rag.config.RagProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Embedding 文本向量化服务 — OpenAI 兼容接口。
 */
@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RagProperties ragProperties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public EmbeddingService(RagProperties ragProperties, ObjectMapper mapper) {
        this.ragProperties = ragProperties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 单文本向量化。
     */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /**
     * 批量向量化。
     */
    public List<float[]> embedBatch(List<String> texts) {
        var emb = ragProperties.getEmbedding();
        try {
            Map<String, Object> body = Map.of(
                    "model", emb.getModel(),
                    "input", texts,
                    "encoding_format", "float"
            );
            String json = mapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(emb.getBaseUrl() + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + emb.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (resp.statusCode() != 200) {
                log.error("Embedding API 返回 {}: {}", resp.statusCode(),
                        resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
                return List.of();
            }

            Map<String, Object> result = mapper.readValue(resp.body(),
                    new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null) return List.of();

            return data.stream().map(d -> {
                @SuppressWarnings("unchecked")
                List<Double> embedding = (List<Double>) d.get("embedding");
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = embedding.get(i).floatValue();
                }
                return vec;
            }).toList();

        } catch (Exception e) {
            log.error("Embedding 调用失败", e);
            return List.of();
        }
    }
}
