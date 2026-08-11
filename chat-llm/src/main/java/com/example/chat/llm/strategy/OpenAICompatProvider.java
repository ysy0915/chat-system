package com.example.chat.llm.strategy;

import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 通用 OpenAI 兼容协议提供商 — 适用于 DeepSeek / 千问 等兼容 /v1/chat/completions 的 API。
 *
 * <p>调用方式: REST (HttpURLConnection)</p>
 */
public class OpenAICompatProvider implements LLMProviderStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatProvider.class);

    /** 请求级覆盖字段（extra 中携带，由本类消费，不透传给厂商） */
    public static final String EXTRA_KEY_BASE_URL = "baseUrl";
    public static final String EXTRA_KEY_API_KEY = "apiKey";

    private final ProviderConfig config;
    private final ObjectMapper mapper;

    public OpenAICompatProvider(ProviderConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String invokeType() {
        return INVOKE_TYPE_REST;
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
        log.debug("{} invoke ENTER model={}", name(), model);
        try {
            Map<String, Object> body = buildRequestBody(request, model, false);
            String json = mapper.writeValueAsString(body);

            HttpURLConnection conn = openConnection(request);
            writeBody(conn, json);

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readAll(conn.getInputStream());
                LangChainResponse r = parseResponse(resp, request.getProvider(), model);
                r.setElapsedMs(System.currentTimeMillis() - start);
                return r;
            } else {
                String err = readAll(conn.getErrorStream());
                log.warn("{} 返回 {}\n请求: {}\n响应: {}", name(), code,
                        json.length() > 500 ? json.substring(0, 500) + "..." : json,
                        err.length() > 500 ? err.substring(0, 500) + "..." : err);
                LangChainResponse r = LangChainResponse.fail(code + ": " + err, request.getProvider());
                r.setElapsedMs(System.currentTimeMillis() - start);
                return r;
            }
        } catch (Throwable t) {
            log.error("{} invoke 异常(Throwable) type={} msg={}", name(), t.getClass().getName(), t.getMessage(), t);
            LangChainResponse r = LangChainResponse.fail(t.getMessage(), request.getProvider());
            r.setElapsedMs(System.currentTimeMillis() - start);
            return r;
        }
    }

    // ========= 流式 SSE =========

    @Override
    public void invokeStream(LangChainRequest request,
                             java.util.function.Consumer<String> chunkConsumer,
                             Runnable onComplete,
                             java.util.function.Consumer<Throwable> onError) {
        String model = resolveModel(request.getModel());
        try {
            Map<String, Object> body = buildRequestBody(request, model, true);
            String json = mapper.writeValueAsString(body);

            HttpURLConnection conn = openConnection(request);
            writeBody(conn, json);

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readAll(conn.getErrorStream());
                onError.accept(new RuntimeException(code + ": " + err));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            Map<String, Object> chunk = mapper.readValue(data,
                                    new TypeReference<Map<String, Object>>() {});
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices =
                                    (List<Map<String, Object>>) chunk.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> delta = (Map<String, Object>)
                                        choices.get(0).get("delta");
                                if (delta != null) {
                                    Object content = delta.get("content");
                                    if (content != null && !"".equals(content)) {
                                        chunkConsumer.accept(content.toString());
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // 跳过无法解析的 chunk
                        }
                    }
                }
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("{} stream 异常", name(), e);
            onError.accept(e);
        }
    }

    // ========= 内部方法 =========

    private Map<String, Object> buildRequestBody(LangChainRequest req, String model, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", stream);

        List<Map<String, Object>> msgs = new ArrayList<>();
        // system prompt 优先
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
            msgs.add(Map.of("role", "system", "content", req.getSystemPrompt()));
        }
        // 用户消息
        if (req.getMessages() != null) {
            msgs.addAll(req.getMessages());
        }
        body.put("messages", msgs);

        if (req.getTemperature() != null) body.put("temperature", req.getTemperature());
        if (req.getMaxTokens() != null) body.put("max_tokens", req.getMaxTokens());
        if (req.getTopP() != null) body.put("top_p", req.getTopP());
        if (req.getStop() != null) body.put("stop", req.getStop());
        if (req.getExtra() != null) {
            // 消费型字段（baseUrl/apiKey 由 openConnection 消费，不透传给厂商）
            Map<String, Object> extra = new LinkedHashMap<>(req.getExtra());
            extra.remove(EXTRA_KEY_BASE_URL);
            extra.remove(EXTRA_KEY_API_KEY);
            body.putAll(extra);
        }
        return body;
    }

    private String resolveModel(String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) return requestModel;
        return config.getModels().get(0); // 默认第一个
    }

    private HttpURLConnection openConnection(LangChainRequest request) throws IOException {
        // 拼接 chat/completions 路径 (不同厂商路径不同，由配置 path 指定)
        String path = config.getPath();
        if (path == null || path.isBlank()) {
            path = "/v1/chat/completions";
        } else if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // 请求级覆盖：chat-core ModelConfig 动态配置的 baseUrl/apiKey 通过 extra 透传
        String baseUrl = config.getBaseUrl();
        String apiKey = config.getApiKey();
        if (request != null && request.getExtra() != null) {
            Object b = request.getExtra().get(EXTRA_KEY_BASE_URL);
            if (b != null && !String.valueOf(b).isBlank()) baseUrl = String.valueOf(b);
            Object k = request.getExtra().get(EXTRA_KEY_API_KEY);
            if (k != null && !String.valueOf(k).isBlank()) apiKey = String.valueOf(k);
        }
        String url = joinUrl(baseUrl, path);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(120_000);
        return conn;
    }

    /**
     * 智能拼接 baseUrl + path，兼容 chat-core DB 动态配置的多种 baseUrl 形式：
     * 1. baseUrl 已包含完整 path（如 .../v1/chat/completions）→ 直接返回 baseUrl
     * 2. baseUrl 以 /v1 结尾而 path 以 /v1 开头（避免双重 /v1 导致 404）
     * 3. 常规拼接
     */
    private String joinUrl(String baseUrl, String path) {
        String base = baseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (path == null || path.isBlank()) return base;
        String p = path.startsWith("/") ? path : "/" + path;
        if (base.endsWith(p)) return base;
        // 最长重叠合并：path 前缀与 base 后缀重叠时合并（如 base=.../v1, path=/v1/chat/completions）
        for (int i = 1; i < p.length(); i++) {
            String suffix = p.substring(0, p.length() - i);
            if (base.endsWith(suffix)) {
                return base + p.substring(p.length() - i);
            }
        }
        return base + p;
    }

    private void writeBody(HttpURLConnection conn, String json) throws IOException {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String s = sb.toString();
            return s.length() > 1000 ? s.substring(0, 1000) + "..." : s;
        }
    }

    @SuppressWarnings("unchecked")
    private LangChainResponse parseResponse(String json, String provider, String model) {
        try {
            Map<String, Object> map = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            String content = "";
            List<Map<String, Object>> toolCalls = null;
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    content = (String) message.getOrDefault("content", "");
                    toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                }
            }
            Map<String, Object> usage = (Map<String, Object>) map.get("usage");

            LangChainResponse r = LangChainResponse.ok(content, provider, model);
            if (usage != null) {
                r.setTotalTokens(toInt(usage.get("total_tokens")));
                r.setPromptTokens(toInt(usage.get("prompt_tokens")));
                r.setCompletionTokens(toInt(usage.get("completion_tokens")));
            }
            r.setToolCalls(toolCalls);
            return r;
        } catch (Exception e) {
            log.warn("解析响应失败: {}", e.getMessage());
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
}
