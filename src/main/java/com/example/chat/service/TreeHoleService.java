package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.repository.TreeHoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class TreeHoleService {

    private static final Logger log = LoggerFactory.getLogger(TreeHoleService.class);

    /** 情绪树洞专属系统 prompt，与其他模块完全隔离 */
    private static final String SYSTEM_PROMPT =
            "你是一个温暖的情感树洞，专门倾听用户内心的情绪与感受。" +
            "你具备以下特点：" +
            "1. 以温暖、包容、不评判的态度倾听和回应；" +
            "2. 先认可用户的感受，让用户感到被理解和接纳；" +
            "3. 给予情感支持，而不是简单地提供建议或解决方案；" +
            "4. 语言温柔亲切，像一个知心朋友；" +
            "5. 适当地引导用户正向思考，但不强行灌输；" +
            "6. 如果用户有心理危机迹象，温和地建议寻求专业帮助。" +
            "每次回复都应该让用户感受到被关爱和理解。";

    @Value("${app.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String defaultBaseUrl;

    @Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @Value("${app.llm.model:qwen-plus}")
    private String defaultModel;

    private final TreeHoleRepository treeHoleRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TreeHoleService(TreeHoleRepository treeHoleRepository,
                           ModelConfigRepository modelConfigRepository,
                           RateLimitService rateLimitService,
                           ObjectMapper objectMapper) {
        this.treeHoleRepository = treeHoleRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();
    }

    /** 固定使用 model_configs 中 id=2 的千问模型配置 */
    private ModelConfig resolveModelConfig() {
        try {
            ModelConfig config = modelConfigRepository.findById(2L);
            if (config != null) return config;
        } catch (Exception e) {
            log.warn("无法读取 model_configs id=2，使用默认配置: {}", e.getMessage());
        }
        // 降级使用 @Value 默认值
        ModelConfig fallback = new ModelConfig();
        fallback.provider = "qwen";
        fallback.model = defaultModel;
        fallback.apiKeyEncrypted = defaultApiKey;
        return fallback;
    }

    private String resolveBaseUrl(ModelConfig config) {
        if (config.metaJson != null && !config.metaJson.isBlank()) {
            try {
                Map<?, ?> meta = objectMapper.readValue(config.metaJson, Map.class);
                Object url = meta.get("base_url");
                if (url != null) return url.toString();
            } catch (Exception ignored) {}
        }
        if (config.provider == null) return defaultBaseUrl;
        return switch (config.provider.toLowerCase()) {
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "qwen"     -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "doubao"   -> "https://ark.cn-beijing.volces.com/api/v3";
            default         -> defaultBaseUrl;
        };
    }

    /** 获取当前用户的树洞历史（最多50条，独立数据表） */
    public List<TreeHoleMessage> getHistory(Long userId) {
        return treeHoleRepository.findByUserId(userId);
    }

    /**
     * 发送情绪内容，构建多轮上下文，调用 AI 并保存到独立表
     */
    public TreeHoleMessage askAndSave(Long userId, String question, String mood) {
        if (!rateLimitService.isAllowed(userId)) {
            long retry = rateLimitService.getRemainingSeconds(userId);
            throw new RuntimeException("发送太频繁，请 " + retry + " 秒后再试");
        }

        // 构建历史上下文（最近10分钟，最多10轮）
        List<TreeHoleMessage> recent = treeHoleRepository.findRecentByUserId(userId);
        List<Map<String, Object>> messages = new ArrayList<>();

        // 系统 prompt
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        // 历史轮次（最多10条）
        int start = Math.max(0, recent.size() - 10);
        for (int i = start; i < recent.size(); i++) {
            TreeHoleMessage prev = recent.get(i);
            messages.add(Map.of("role", "user", "content", prev.question));
            if (prev.answerJson != null && !prev.answerJson.isBlank()) {
                messages.add(Map.of("role", "assistant", "content", prev.answerJson));
            }
        }

        // 当前问题（附带情绪标签）
        String fullQuestion = (mood != null && !mood.isBlank())
                ? "[情绪：" + mood + "] " + question
                : question;
        messages.add(Map.of("role", "user", "content", fullQuestion));

        // 保存记录（status=pending）
        TreeHoleMessage m = new TreeHoleMessage();
        m.reqId = UUID.randomUUID().toString();
        m.userId = userId;
        m.question = question;
        m.mood = mood;
        m.status = "pending";
        treeHoleRepository.insert(m);

        // 解析模型配置（从数据库读取，与 ChatProcessor 一致）
        ModelConfig config = resolveModelConfig();
        String effectiveApiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;
        String effectiveBaseUrl = resolveBaseUrl(config);
        String effectiveModel = (config.model != null && !config.model.isBlank())
                ? config.model : defaultModel;

        // 调用 AI
        try {
            String answer = callLLM(effectiveBaseUrl, effectiveApiKey, effectiveModel, messages);
            m.answerJson = answer;
            m.status = "done";
        } catch (Exception e) {
            log.error("TreeHole AI 调用失败: {}", e.getMessage());
            m.answerJson = "树洞暂时出了点小问题，请稍后再试...";
            m.status = "error";
        }

        treeHoleRepository.updateByReqId(m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private String callLLM(String baseUrl, String apiKey, String model,
                           List<Map<String, Object>> messages) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.85);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API returned status " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM API returned no choices");
        }
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        return msg != null && msg.get("content") != null ? msg.get("content").toString() : "无回应";
    }
}
