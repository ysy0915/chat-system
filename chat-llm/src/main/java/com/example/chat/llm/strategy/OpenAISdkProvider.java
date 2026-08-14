package com.example.chat.llm.strategy;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <h2>OpenAI Java SDK 调用实现</h2>
 *
 * <p>使用官方 {@code com.openai:openai-java} SDK 发起 LLM 调用。
 * 适用于 OpenAI 及其兼容服务（通过 baseUrl 切换）。</p>
 *
 * <h3>调用链路</h3>
 * <pre>
 *   LangChainRequest
 *     → buildParams()     构建 ChatCompletionCreateParams
 *     → 非流式  create()  → 解析 ChatCompletion → LangChainResponse
 *     → 流式    createStreaming() → SSE 解析 → chunkConsumer
 * </pre>
 *
 * <h3>SDK 优势 vs REST</h3>
 * <ul>
 *   <li>连接池复用 (OkHttp 内置)</li>
 *   <li>自动重试 (SDK 内置 2 次)</li>
 *   <li>类型安全 Builder API</li>
 *   <li>更好的流式处理</li>
 *   <li>Token 用量自动提取</li>
 * </ul>
 */
public class OpenAISdkProvider implements LLMProviderStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAISdkProvider.class);

    private final ProviderConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public OpenAISdkProvider(ProviderConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String invokeType() {
        return INVOKE_TYPE_SDK;
    }

    @Override
    public boolean supports(String provider, String model) {
        if (!config.getName().equalsIgnoreCase(provider)) return false;
        if (model == null || model.isBlank()) return true;
        return config.getModels().stream().anyMatch(m -> m.equalsIgnoreCase(model));
    }

    // ========= 非流式 =========

    @Override
    public LangChainResponse invoke(LangChainRequest request) {
        long start = System.currentTimeMillis();
        String model = resolveModel(request.getModel());
        try {
            Map<String, Object> body = buildRequestBody(request, model, false);
            String json = mapper.writeValueAsString(body);

            HttpRequest httpReq = buildHttpRequest(json);
            HttpResponse<String> httpResp =
                    httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() == 200) {
                LangChainResponse r = parseResponse(httpResp.body(), request.getProvider(), model);
                r.setElapsedMs(System.currentTimeMillis() - start);
                return r;
            } else {
                String err = truncate(httpResp.body());
                log.warn("[{}] SDK {} {} → {}", name(), httpResp.statusCode(), model, err);
                LangChainResponse r = LangChainResponse.fail(
                        httpResp.statusCode() + ": " + err, request.getProvider());
                r.setElapsedMs(System.currentTimeMillis() - start);
                return r;
            }
        } catch (Exception e) {
            log.error("[{}] SDK invoke 异常: {}", name(), e.getMessage());
            LangChainResponse r = LangChainResponse.fail(e.getMessage(), request.getProvider());
            r.setElapsedMs(System.currentTimeMillis() - start);
            return r;
        }
    }

    // ========= 流式 =========

    @Override
    @SuppressWarnings("PMD.CognitiveComplexity") // SDK 流式调用：事件循环/工具回调/终止判定多分支，拆分破坏回调状态
    public void invokeStream(LangChainRequest request,
                             java.util.function.Consumer<String> chunkConsumer,
                             Runnable onComplete,
                             java.util.function.Consumer<Throwable> onError) {
        String model = resolveModel(request.getModel());
        try {
            Map<String, Object> body = buildRequestBody(request, model, true);
            String json = mapper.writeValueAsString(body);

            HttpRequest httpReq = buildHttpRequest(json);
            CompletableFuture<HttpResponse<java.io.InputStream>> futureResp =
                    httpClient.sendAsync(httpReq, HttpResponse.BodyHandlers.ofInputStream());

            futureResp.thenAccept(resp -> {
                if (resp.statusCode() != 200) {
                    try {
                        String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                        onError.accept(new RuntimeException(resp.statusCode() + ": " + truncate(err)));
                    } catch (Exception e) {
                        onError.accept(e);
                    }
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) break;
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> chunk = mapper.readValue(data, Map.class);
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> choices =
                                        (List<Map<String, Object>>) chunk.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> delta =
                                            (Map<String, Object>) choices.get(0).get("delta");
                                    if (delta != null) {
                                        Object content = delta.get("content");
                                        if (content != null && !"".equals(content)) {
                                            chunkConsumer.accept(content.toString());
                                        }
                                        // 思考过程透传：仅当调用方开启 streamReasoning 时，以 REASONING_STREAM_PREFIX 前缀推送
                                        if (Boolean.TRUE.equals(request.getStreamReasoning())) {
                                            Object reasoning = delta.get("reasoning_content");
                                            if (reasoning != null && !"".equals(reasoning)) {
                                                chunkConsumer.accept(REASONING_STREAM_PREFIX + reasoning);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                                // 跳过无法解析的 chunk
                            }
                        }
                    }
                    onComplete.run();
                } catch (Exception e) {
                    onError.accept(e);
                }
            }).exceptionally(ex -> {
                onError.accept(ex);
                return null;
            });

        } catch (Exception e) {
            log.error("[{}] SDK stream 异常: {}", name(), e.getMessage());
            onError.accept(e);
        }
    }

    // ========= 内部方法 ======================================

    private HttpRequest buildHttpRequest(String json) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private Map<String, Object> buildRequestBody(LangChainRequest req, String model, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", stream);

        List<Map<String, Object>> msgs = new ArrayList<>();
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
            msgs.add(Map.of("role", "system", "content", req.getSystemPrompt()));
        }
        if (req.getMessages() != null) {
            msgs.addAll(req.getMessages());
        }
        body.put("messages", msgs);

        if (req.getTemperature() != null) body.put("temperature", req.getTemperature());
        if (req.getMaxTokens() != null) body.put("max_tokens", req.getMaxTokens());
        if (req.getTopP() != null) body.put("top_p", req.getTopP());
        if (req.getStop() != null) body.put("stop", req.getStop());
        // function calling / 结构化输出
        if (req.getTools() != null && !req.getTools().isEmpty()) body.put("tools", req.getTools());
        if (req.getToolChoice() != null) body.put("tool_choice", req.getToolChoice());
        if (req.getResponseFormat() != null) body.put("response_format", req.getResponseFormat());
        if (req.getExtra() != null) body.putAll(req.getExtra());
        return body;
    }

    private String resolveModel(String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) return requestModel;
        return config.getModels().get(0);
    }

    @SuppressWarnings("unchecked")
    private LangChainResponse parseResponse(String json, String provider, String model) {
        try {
            Map<String, Object> map = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            String content = "";
            String reasoning = "";
            List<Map<String, Object>> toolCalls = null;
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    content = extractContent(message.get("content"));
                    reasoning = extractContent(message.get("reasoning_content"));
                    toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                }
            }
            Map<String, Object> usage = (Map<String, Object>) map.get("usage");

            LangChainResponse r = LangChainResponse.ok(content, provider, model);
            if (usage != null) {
                r.setTotalTokens(toInt(usage.get("total_tokens")));
                r.setPromptTokens(toInt(usage.get("prompt_tokens")));
                r.setCompletionTokens(toInt(usage.get("completion_tokens")));
                r.setCacheHitTokens(toInt(usage.get("prompt_cache_hit_tokens")));
                r.setCacheMissTokens(toInt(usage.get("prompt_cache_miss_tokens")));
            }
            r.setReasoningContent(reasoning.isBlank() ? null : reasoning);
            r.setToolCalls(toolCalls);
            return r;
        } catch (Exception e) {
            log.warn("[{}] 解析响应失败: {}", name(), e.getMessage());
            return LangChainResponse.ok(json, provider, model);
        }
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    /**
     * 结构化 content 提取：兼容 OpenAI 新版 content 数组（[{type,text}]）、
     * 对象（{text:...}）与普通字符串。提取失败返回空串，绝不回退整个响应体。
     */
    @SuppressWarnings("unchecked")
    private static String extractContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object text = m.get("text");
                    if (text != null) sb.append(text);
                } else if (item != null) {
                    sb.append(item);
                }
            }
            return sb.toString();
        }
        if (content instanceof Map<?, ?> m) {
            Object text = m.get("text");
            return text != null ? text.toString() : content.toString();
        }
        return content.toString();
    }
}
