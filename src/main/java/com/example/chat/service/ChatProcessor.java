package com.example.chat.service;

import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatProcessor {
    private final MessageRepository messageRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService modelExecutor;

    @org.springframework.beans.factory.annotation.Value("${app.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String defaultBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @org.springframework.beans.factory.annotation.Value("${app.llm.model:qwen-plus}")
    private String defaultModel;

    @org.springframework.beans.factory.annotation.Value("${app.llm.provider:qwen}")
    private String defaultProvider;

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    public ChatProcessor(MessageRepository messageRepository,
                         ModelConfigRepository modelConfigRepository,
                         SimpMessagingTemplate messagingTemplate,
                         RedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.modelExecutor = Executors.newFixedThreadPool(3);
    }

    public void process(Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();

        try {
            String cached = null;
            try {
                cached = redisTemplate.opsForValue().get(buildCacheKey(question));
            } catch (Exception ex) {
                System.err.println("[WARN] Redis read failed, skipping cache: " + ex.getMessage());
            }

            if (cached != null) {
                messagingTemplate.convertAndSend("/topic/user." + userId,
                        Map.of("type", "done", "req_id", reqId, "answer", cached));
                Message m = messageRepository.findByReqId(reqId);
                if (m != null) {
                    m.answerJson = cached;
                    m.status = "done";
                    messageRepository.updateByReqId(m);
                }
                return;
            }

            List<Long> chatModelIds = List.of(1L, 2L, 3L);
            List<ModelConfig> configs = modelConfigRepository.findByIds(chatModelIds).stream()
                    .filter(config -> config.enabled != null && config.enabled)
                    .sorted(Comparator.comparingInt(config -> config.priority != null ? config.priority : 100))
                    .toList();

            if (configs.isEmpty()) {
                ModelConfig fallback = new ModelConfig();
                fallback.provider = defaultProvider;
                fallback.model = defaultModel;
                fallback.apiKeyEncrypted = defaultApiKey;
                fallback.priority = 100;
                fallback.enabled = true;
                configs = List.of(fallback);
            }

            List<CompletableFuture<LLMResult>> futures = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            for (ModelConfig config : configs) {
                CompletableFuture<LLMResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        String effectiveApiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                                ? config.apiKeyEncrypted
                                : defaultApiKey;
                        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                            throw new IllegalStateException("未配置 API Key");
                        }
                        String effectiveBaseUrl = resolveBaseUrl(config);
                        String answer = callLLM(effectiveBaseUrl, effectiveApiKey, config.model, question);
                        return new LLMResult(config, answer);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }, modelExecutor);
                futures.add(future);

                future.thenAccept(result -> {
                    if (completed.compareAndSet(false, true)) {
                        completeWithAnswer(reqId, userId, question, result.answer, result.config.provider, result.config.model);
                    }
                }).exceptionally(ex -> {
                    return null;
                });
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((unused, ex) -> {
                if (!completed.get()) {
                    String message = ex != null ? ex.getMessage() : "所有模型调用均失败";
                    messagingTemplate.convertAndSend("/topic/user." + userId,
                            Map.of("type", "error", "req_id", reqId, "message", message));
                }
            });

        } catch (Exception ex) {
            System.err.println("[ERROR] ChatProcessor: " + ex.getMessage());
            ex.printStackTrace();
            messagingTemplate.convertAndSend("/topic/user." + userId,
                    Map.of("type", "error", "req_id", reqId, "message", ex.getMessage()));
        }
    }

    private String callLLM(String baseUrl, String apiKey, String model, String question) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", question)),
                "temperature", 0.7
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API returned status " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM API returned no choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return message != null ? message.get("content").toString() : "No response";
    }

    private boolean isTargetProvider(ModelConfig config) {
        if (config == null || config.provider == null || config.provider.isBlank()) {
            return false;
        }
        String provider = config.provider.trim().toLowerCase();
        return "deepseek".equals(provider) || "qwen".equals(provider) || "doubao".equals(provider);
    }

    private void completeWithAnswer(String reqId, Long userId, String question, String answer, String provider, String model) {
        messagingTemplate.convertAndSend("/topic/user." + userId,
                Map.of("type", "done", "req_id", reqId, "answer", answer));

        String cacheKey = buildCacheKey(question, provider, model);
        try {
            redisTemplate.opsForValue().set(cacheKey, answer, CACHE_TTL);
        } catch (Exception ex) {
            System.err.println("[WARN] Redis write failed, skipping cache: " + ex.getMessage());
        }

        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            } catch (Exception ex) {
                m.answerJson = answer;
            }
            m.status = "done";
            m.provider = provider;
            m.model = model;
            messageRepository.updateByReqId(m);
            System.out.println("[DEBUG] DB updated: reqId=" + reqId + " status=done provider=" + provider + " model=" + model);
        } else {
            System.err.println("[WARN] Message not found for reqId=" + reqId);
        }

        System.out.println("[DEBUG] ChatProcessor: LLM call done for reqId=" + reqId + " provider=" + provider + " model=" + model);
    }

    private String resolveBaseUrl(ModelConfig config) {
        if (config.metaJson != null && !config.metaJson.isBlank()) {
            try {
                Map<String, Object> meta = objectMapper.readValue(config.metaJson, Map.class);
                Object baseUrl = meta.get("base_url");
                if (baseUrl != null) {
                    return baseUrl.toString();
                }
            } catch (Exception ignored) {}
        }
        switch (config.provider.toLowerCase()) {
            case "deepseek": return "https://api.deepseek.com/v1";
            case "qwen": return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "doubao": return "https://ark.cn-beijing.volces.com/api/v3";
            default: return "https://api.openai.com/v1";
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String buildCacheKey(String question) {
        return "question:" + sha256(question + "::model-pool");
    }

    private String buildCacheKey(String question, String provider, String model) {
        return "question:" + sha256(question + "::" + (provider == null ? "" : provider) + "::" + (model == null ? "" : model));
    }

    private static class LLMResult {
        private final ModelConfig config;
        private final String answer;

        private LLMResult(ModelConfig config, String answer) {
            this.config = config;
            this.answer = answer;
        }
    }
}
