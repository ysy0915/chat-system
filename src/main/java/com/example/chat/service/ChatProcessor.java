package com.example.chat.service;

import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

@Service
public class ChatProcessor {
    private static final Logger log = LoggerFactory.getLogger(ChatProcessor.class);
    private final MessageRepository messageRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService modelExecutor;
    private final BroadcastService broadcastService;

    @org.springframework.beans.factory.annotation.Value("${app.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String defaultBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @org.springframework.beans.factory.annotation.Value("${app.llm.model:qwen-plus}")
    private String defaultModel;

    @org.springframework.beans.factory.annotation.Value("${app.llm.provider:qwen}")
    private String defaultProvider;

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);

    public ChatProcessor(MessageRepository messageRepository,
                         ModelConfigRepository modelConfigRepository,
                         SimpMessagingTemplate messagingTemplate,
                         RedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper,
                         BroadcastService broadcastService) {
        this.messageRepository = messageRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.modelExecutor = new ThreadPoolExecutor(
                3,
                30,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "llm-worker-" + System.currentTimeMillis());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void process(Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        boolean isPrivate = "true".equals(String.valueOf(payload.get("private")));

        try {
            List<ModelConfig> allConfigs = modelConfigRepository.findAllEnabled().stream()
                    .filter(c -> c.modelType == null || "chat".equalsIgnoreCase(c.modelType))
                    .sorted(Comparator.comparingInt(config -> config.priority != null ? config.priority : 100))
                    .toList();

            if (allConfigs.isEmpty()) {
                ModelConfig fallback = new ModelConfig();
                fallback.provider = defaultProvider;
                fallback.model = defaultModel;
                fallback.apiKeyEncrypted = defaultApiKey;
                fallback.priority = 100;
                fallback.enabled = true;
                allConfigs = List.of(fallback);
            }

            if (isPrivate) {
                String switchResult = trySwitchModel(userId, question, allConfigs);
                if (switchResult != null) {
                    // switchResult 已是 {"answer":"..."} 合法 JSON
                    String displayText = switchResult;
                    try {
                        Map<?, ?> parsed = objectMapper.readValue(switchResult, Map.class);
                        displayText = (String) parsed.get("answer");
                    } catch (Exception ignored) {}
                    Message m = messageRepository.findByReqId(reqId);
                    if (m != null) {
                        m.answerJson = switchResult;
                        m.status = "done";
                        messageRepository.updateByReqId(m);
                    }
                    broadcastService.broadcast("/topic/user." + userId,
                            Map.of("type", "done", "req_id", reqId, "answer", displayText));
                    return;
                }
            }

            String cached = null;
            try {
                cached = redisTemplate.opsForValue().get(buildCacheKey(question));
            } catch (Exception ex) {
                log.warn("[WARN] Redis read failed, skipping cache: {}", ex.getMessage());
            }

            if (cached != null) {
                broadcastService.broadcast("/topic/user." + userId,
                        Map.of("type", "done", "req_id", reqId, "answer", cached));
                Message m = messageRepository.findByReqId(reqId);
                if (m != null) {
                    try {
                        m.answerJson = objectMapper.writeValueAsString(Map.of("answer", cached));
                    } catch (Exception e) {
                        m.answerJson = "{\"answer\":\"\"}";
                    }
                    m.status = "done";
                    messageRepository.updateByReqId(m);
                }
                return;
            }

            List<ModelConfig> configs;
            Long boundModelId = getPersonalModelId(userId);
            if (isPrivate && boundModelId == null) {
                // 个人对话空间：未绑定模型时，优先使用 DeepSeek
                List<ModelConfig> deepseekConfigs = allConfigs.stream()
                        .filter(c -> "deepseek".equalsIgnoreCase(c.provider))
                        .toList();
                configs = deepseekConfigs.isEmpty() ? allConfigs : deepseekConfigs;
            } else if (boundModelId != null) {
                configs = allConfigs.stream()
                        .filter(c -> c.id != null && c.id.equals(boundModelId))
                        .toList();
                if (configs.isEmpty()) {
                    // 绑定的模型不存在或非 chat 类型，回退到 deepseek，再回退到全部
                    configs = allConfigs.stream()
                            .filter(c -> "deepseek".equalsIgnoreCase(c.provider))
                            .toList();
                    configs = configs.isEmpty() ? allConfigs : configs;
                    log.warn("[WARN] 用户 {} 绑定的模型ID={} 不在可用chat模型中，回退到 {}", userId, boundModelId,
                            configs.isEmpty() ? "无可用模型" : configs.get(0).provider);
                }
            } else {
                List<ModelConfig> doubaoConfigs = allConfigs.stream()
                        .filter(c -> "doubao".equalsIgnoreCase(c.provider))
                        .toList();
                configs = doubaoConfigs.isEmpty() ? allConfigs : doubaoConfigs;
            }

            if (configs.isEmpty()) {
                log.error("[ERROR] 用户 {} 没有可用的 chat 模型, allConfigs={}", userId,
                        allConfigs.stream().map(c -> c.provider + "/" + c.model).toList());
                broadcastService.broadcast("/topic/user." + userId,
                        Map.of("type", "error", "req_id", reqId, "message", "没有可用的对话模型"));
                return;
            }

            final List<Map<String, Object>> historyMessages;
            if (isPrivate) {
                historyMessages = buildHistoryMessages(userId, question);
            } else {
                historyMessages = null;
            }

            List<CompletableFuture<LLMResult>> futures = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicInteger finishedCount = new AtomicInteger(0);
            int totalModels = configs.size();

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
                        String answer;
                        if (historyMessages != null) {
                            answer = callLLMWithHistory(effectiveBaseUrl, effectiveApiKey, config.model, historyMessages, config.provider);
                        } else {
                            answer = callLLM(effectiveBaseUrl, effectiveApiKey, config.model, question, config.provider);
                        }
                        return new LLMResult(config, answer);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }, modelExecutor);

                future.handle((result, ex) -> {
                    int finished = finishedCount.incrementAndGet();

                    if (ex != null) {
                        log.error("[ERROR] 模型 {} 调用失败: {}", config.model,
                                ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    } else {
                        log.info("[INFO] 模型 {} 返回成功, answer长度={}", config.model,
                                result.answer != null ? result.answer.length() : 0);

                        if (completed.compareAndSet(false, true)) {
                            log.info("[INFO] 抢到首发, 开始 completeWithAnswer, reqId={}", reqId);
                            try {
                                if (isPrivate) {
                                    savePersonalModelId(userId, result.config.id);
                                }
                                completeWithAnswer(reqId, userId, question, result.answer, result.config.provider, result.config.model);
                                log.info("[INFO] completeWithAnswer 完成, reqId={}", reqId);
                            } catch (Exception e) {
                                log.error("[ERROR] completeWithAnswer 失败: {}", e.getMessage(), e);
                            }
                        } else {
                            log.info("[INFO] 模型 {} 不是首发, 跳过", config.model);
                        }
                    }

                    // 最后一个完成的检查是否全部失败
                    if (finished == totalModels && !completed.get()) {
                        log.warn("[WARN] 所有 {} 个模型都失败了, 广播错误: reqId={} userId={}", totalModels, reqId, userId);
                        broadcastService.broadcast("/topic/user." + userId,
                                Map.of("type", "error", "req_id", reqId, "message", "所有模型调用均失败"));
                    }

                    return null;
                });
                futures.add(future);
            }

        } catch (Exception ex) {
            log.error("[ERROR] ChatProcessor: {}", ex.getMessage(), ex);
            broadcastService.broadcast("/topic/user." + userId,
                    Map.of("type", "error", "req_id", reqId, "message", ex.getMessage()));
        }
    }

    public void processWithFile(String reqId, Long userId, String question, String fileName, byte[] fileContent, String mimeType) {
        try {
            log.info("[INFO] processWithFile 开始处理: reqId={}, fileName={}, mimeType={}, question={}", reqId, fileName, mimeType, question);
            final String lowerName = fileName != null ? fileName.toLowerCase() : "";
            final boolean isImage = mimeType != null && mimeType.startsWith("image/");
            final boolean isExcel = lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls");
            final boolean isPpt = lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt");
            final boolean isCsv = lowerName.endsWith(".csv");
            log.info("[INFO] 文件类型判断: isImage={}, isExcel={}, isPpt={}", isImage, isExcel, isPpt);

            final String fileTextContent;
            final String fileBase64;

            if (isImage) {
                fileBase64 = Base64.getEncoder().encodeToString(fileContent);
                fileTextContent = null;
            } else if (isExcel) {
                fileBase64 = null;
                String extracted = extractExcelContent(fileContent);
                if (extracted.length() > 30000) {
                    extracted = extracted.substring(0, 30000) + "\n...[文件内容过长，已截断]";
                }
                fileTextContent = extracted;
            } else if (isPpt) {
                fileBase64 = null;
                String extracted = extractPptContent(fileContent);
                if (extracted.length() > 30000) {
                    extracted = extracted.substring(0, 30000) + "\n...[文件内容过长，已截断]";
                }
                fileTextContent = extracted;
            } else {
                fileBase64 = null;
                String raw = new String(fileContent, StandardCharsets.UTF_8);
                if (raw.length() > 30000) {
                    raw = raw.substring(0, 30000) + "\n...[文件内容过长，已截断]";
                }
                fileTextContent = raw;
            }

            List<ModelConfig> allConfigs = modelConfigRepository.findAllEnabled().stream()
                    .sorted(Comparator.comparingInt(config -> config.priority != null ? config.priority : 100))
                    .toList();

            if (allConfigs.isEmpty()) {
                ModelConfig fallback = new ModelConfig();
                fallback.provider = defaultProvider;
                fallback.model = defaultModel;
                fallback.apiKeyEncrypted = defaultApiKey;
                fallback.priority = 100;
                fallback.enabled = true;
                allConfigs = List.of(fallback);
            }

            List<ModelConfig> configs;
            if (isImage) {
                // 图片请求：强制使用 id=8 的 image_parse 专用模型
                List<ModelConfig> imageParse = allConfigs.stream()
                        .filter(c -> c.id != null && c.id == 8L && "image_parse".equals(c.modelType))
                        .toList();
                if (imageParse.isEmpty()) {
                    imageParse = allConfigs.stream()
                            .filter(c -> "image_parse".equals(c.modelType))
                            .toList();
                }
                configs = imageParse.isEmpty() ? allConfigs : imageParse;
                log.info("[INFO] 图片请求，强制使用 image_parse 模型");
            } else {
                // 文档/文件解析：强制使用 id=9 的 text_parse 专用模型（智谱 glm-4.6v-flash）
                List<ModelConfig> textParse = allConfigs.stream()
                        .filter(c -> c.id != null && c.id == 9L && "text_parse".equals(c.modelType))
                        .toList();
                if (textParse.isEmpty()) {
                    // 兜底：按 model_type=text_parse 筛选
                    textParse = allConfigs.stream()
                            .filter(c -> "text_parse".equals(c.modelType) && Boolean.TRUE.equals(c.enabled))
                            .toList();
                }
                if (!textParse.isEmpty()) {
                    configs = textParse;
                    log.info("[INFO] 文档解析请求，强制使用 text_parse 模型");
                } else {
                    // 极端兜底：找不到 text_parse 模型时退回原逻辑
                    Long boundModelId = getPersonalModelId(userId);
                    if (boundModelId != null) {
                        List<ModelConfig> bound = allConfigs.stream()
                                .filter(c -> c.id != null && c.id.equals(boundModelId))
                                .toList();
                        configs = bound.isEmpty() ? allConfigs : bound;
                    } else {
                        List<ModelConfig> textConfigs = allConfigs.stream()
                                .filter(c -> "qwen".equalsIgnoreCase(c.provider))
                                .toList();
                        configs = textConfigs.isEmpty() ? allConfigs : textConfigs;
                    }
                }
            }

            final List<Map<String, Object>> fileHistoryMessages = buildFileHistoryMessages(userId, question, fileName, fileTextContent, isImage, fileBase64, mimeType);

            log.info("[INFO] 筛选后的模型配置数量: {}", configs.size());
            for (ModelConfig cfg : configs) {
                log.info("  - 模型: {}/{} (id={}, enabled={})", cfg.provider, cfg.model, cfg.id, cfg.enabled);
            }

            List<CompletableFuture<?>> futures = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            for (ModelConfig config : configs) {
                CompletableFuture<?> fullFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        String effectiveApiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                                ? config.apiKeyEncrypted
                                : defaultApiKey;
                        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                            throw new IllegalStateException("未配置 API Key");
                        }
                        String effectiveBaseUrl = resolveBaseUrl(config);
                        String answer = callLLMWithHistory(effectiveBaseUrl, effectiveApiKey, config.model, fileHistoryMessages, config.provider, isImage);
                        return new LLMResult(config, answer);
                    } catch (Exception ex) {
                        log.error("[ERROR] processWithFile 模型调用失败 [{}/{}]: {}", config.provider, config.model, ex.getMessage());
                        if (ex.getCause() != null) {
                            log.error("  根本原因: {}", ex.getCause().getMessage());
                        }
                        return null;
                    }
                }, modelExecutor).thenAccept(result -> {
                    // thenAccept 也在 allOf 等待范围内，确保 completed 先被设置
                    if (result != null && completed.compareAndSet(false, true)) {
                        savePersonalModelId(userId, result.config.id);
                        completeWithAnswer(reqId, userId, question, result.answer, result.config.provider, result.config.model);
                    }
                });

                futures.add(fullFuture);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((unused, ex) -> {
                if (!completed.get()) {
                    broadcastService.broadcast("/topic/user." + userId,
                            Map.of("type", "error", "req_id", reqId, "message", "所有模型调用均失败，请检查模型配置或稍后重试"));
                }
            });

        } catch (Exception ex) {
            log.error("[ERROR] ChatProcessor processWithFile: {}", ex.getMessage());
            broadcastService.broadcast("/topic/user." + userId,
                    Map.of("type", "error", "req_id", reqId, "message", ex.getMessage()));
        }
    }

    private String callLLM(String baseUrl, String apiKey, String model, String question) throws Exception {
        return callLLMInternal(baseUrl, apiKey, model, question, null, null, null);
    }

    private String callLLM(String baseUrl, String apiKey, String model, String question, String provider) throws Exception {
        boolean enableSearch = "qwen".equalsIgnoreCase(provider) || "doubao".equalsIgnoreCase(provider);
        return callLLMInternal(baseUrl, apiKey, model, question, null, null, enableSearch ? provider : null);
    }

    private String callLLMInternal(String baseUrl, String apiKey, String model, String question,
                                   String fileContent, String fileName, String searchProvider) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        String content;
        if (fileContent != null && fileName != null) {
            content = question + "\n\n--- 以下是文件 [" + fileName + "] 的内容 ---\n" + fileContent + "\n--- 文件内容结束 ---";
        } else {
            content = question;
        }

        Map<String, Object> messageBody = Map.of("role", "user", "content", content);
        List<Object> messages = new ArrayList<>();
        messages.add(messageBody);

        java.util.LinkedHashMap<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);

        if ("qwen".equalsIgnoreCase(searchProvider) || "doubao".equalsIgnoreCase(searchProvider)) {
            requestBody.put("enable_search", true);
        }

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

    private String callLLMWithFileContent(String baseUrl, String apiKey, String model, String question, String fileContent, String fileName) throws Exception {
        return callLLMInternal(baseUrl, apiKey, model, question, fileContent, fileName, null);
    }

    private List<Map<String, Object>> buildHistoryMessages(Long userId, String currentQuestion) {
        List<Message> recent = getRecentMessages(userId);
        if (recent == null || recent.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message m : recent) {
            messages.add(Map.of("role", "user", "content", m.question));
            messages.add(Map.of("role", "assistant", "content", m.answerJson));
        }
        messages.add(Map.of("role", "user", "content", currentQuestion));
        return messages;
    }

    private List<Message> getRecentMessages(Long userId) {
        try {
            List<Message> recent = messageRepository.findRecentByUserId(userId);
            if (recent != null && !recent.isEmpty()) {
                return recent;
            }
        } catch (Exception ex) {
            log.warn("[WARN] Failed to load recent messages: {}", ex.getMessage());
        }
        return null;
    }

    private List<Map<String, Object>> buildFileHistoryMessages(Long userId, String question, String fileName,
                                                                String fileTextContent, boolean isImage,
                                                                String fileBase64, String mimeType) {
        List<Message> recent = getRecentMessages(userId);
        List<Map<String, Object>> messages = new ArrayList<>();

        if (recent != null) {
            for (Message m : recent) {
                messages.add(Map.of("role", "user", "content", m.question));
                messages.add(Map.of("role", "assistant", "content", m.answerJson));
            }
        }

        if (isImage) {
            String imageQuestion = (question == null || question.isBlank()) ? "请描述这张图片" : question;
            List<Map<String, Object>> contentParts = new ArrayList<>();
            contentParts.add(Map.of("type", "text", "text", imageQuestion));
            contentParts.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", "data:" + mimeType + ";base64," + fileBase64)));
            messages.add(Map.of("role", "user", "content", contentParts));
        } else {
            String content = question + "\n\n--- 以下是文件 [" + fileName + "] 的内容 ---\n" + fileTextContent + "\n--- 文件内容结束 ---";
            messages.add(Map.of("role", "user", "content", content));
        }

        return messages;
    }

    private String callLLMWithHistory(String baseUrl, String apiKey, String model,
                                       List<Map<String, Object>> messages, String provider) throws Exception {
        return callLLMWithHistory(baseUrl, apiKey, model, messages, provider, false);
    }

    private String callLLMWithHistory(String baseUrl, String apiKey, String model,
                                       List<Map<String, Object>> messages, String provider, boolean isVision) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        java.util.LinkedHashMap<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);

        // 图片/多模态请求不能同时开启 enable_search
        if (!isVision && ("qwen".equalsIgnoreCase(provider) || "doubao".equalsIgnoreCase(provider))) {
            requestBody.put("enable_search", true);
        }

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        log.info("[INFO] callLLMWithHistory -> POST {} model={}", url, model);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("[INFO] callLLMWithHistory <- status={} bodyLen={}", response.statusCode(),
                response.body() != null ? response.body().length() : 0);
        if (response.statusCode() != 200) {
            log.error("[ERROR] LLM API 返回错误: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("LLM API returned status " + response.statusCode() + ": " + response.body());
        }

        try {
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("[ERROR] LLM API 返回无 choices, body={}", response.body());
                throw new RuntimeException("LLM API returned no choices");
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                log.error("[ERROR] LLM API 返回无 message, body={}", response.body());
                throw new RuntimeException("LLM API returned no message");
            }
            Object content = message.get("content");
            if (content == null) {
                log.error("[ERROR] LLM API 返回无 content, body={}", response.body());
                throw new RuntimeException("LLM API returned no content");
            }
            return content.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ERROR] LLM 响应解析失败: {}, body={}", e.getMessage(), response.body(), e);
            throw new RuntimeException("LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    private String callLLMWithImage(String baseUrl, String apiKey, String model, String question, String imageBase64, String mimeType) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", question));
        contentParts.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mimeType + ";base64," + imageBase64)));

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", contentParts)),
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

        log.info("[INFO] callLLMWithHistory -> POST {} model={}", url, model);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("[INFO] callLLMWithHistory <- status={}", response.statusCode());
        if (response.statusCode() != 200) {
            log.error("[ERROR] LLM API 返回错误: status={}, body={}", response.statusCode(), response.body());
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

    private String extractExcelContent(byte[] fileContent) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(fileContent))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) { sb.append("\n"); continue; }
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        XSSFCell cell = row.getCell(c);
                        cells.add(cell != null ? cell.toString().trim() : "");
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String extractPptContent(byte[] fileContent) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new java.io.ByteArrayInputStream(fileContent))) {
            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                sb.append("=== Slide ").append(i + 1).append(" ===\n");
                for (var shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText().trim();
                        if (!text.isEmpty()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean isTargetProvider(ModelConfig config) {
        if (config == null || config.provider == null || config.provider.isBlank()) {
            return false;
        }
        String provider = config.provider.trim().toLowerCase();
        return "deepseek".equals(provider) || "qwen".equals(provider) || "doubao".equals(provider) || "zhipu".equals(provider);
    }

    private void completeWithAnswer(String reqId, Long userId, String question, String answer, String provider, String model) {
        broadcastService.broadcast("/topic/user." + userId,
                Map.of("type", "done", "req_id", reqId, "answer", answer));

        broadcastService.broadcast("/topic/public-questions",
                Map.of("type", "answer", "req_id", reqId, "user_id", userId, "answer", answer));

        String cacheKey = buildCacheKey(question, provider, model);
        try {
            redisTemplate.opsForValue().set(cacheKey, answer, CACHE_TTL);
        } catch (Exception ex) {
            log.warn("[WARN] Redis write failed, skipping cache: {}", ex.getMessage());
        }

        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            } catch (Exception e) {
                m.answerJson = "{\"answer\":\"\"}";
            }
            m.status = "done";
            m.provider = provider;
            m.model = model;
            messageRepository.updateByReqId(m);
            log.debug("[DEBUG] DB updated: reqId={} status=done provider={} model={}", reqId, provider, model);
        } else {
            log.warn("[WARN] Message not found for reqId={}", reqId);
        }

        log.debug("[DEBUG] ChatProcessor: LLM call done for reqId={} provider={} model={}", reqId, provider, model);
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
            case "zhipu": return "https://open.bigmodel.cn/api/paas/v4";
            default: return "https://api.openai.com/v1";
        }
    }

    private boolean isVisionSupported(String provider) {
        if (provider == null) return false;
        switch (provider.toLowerCase()) {
            case "qwen":
            case "doubao":
            case "openai":
            case "azure":
                return true;
            default:
                return false;
        }
    }

    private boolean isVisionModel(ModelConfig config) {
        // 优先按 modelType 字段判断
        if ("image_parse".equals(config.modelType)) return true;
        // 兜底：按 provider 判断
        return isVisionSupported(config.provider);
    }

    private String resolveVisionModel(ModelConfig config) {
        String provider = config.provider != null ? config.provider.toLowerCase() : "";
        switch (provider) {
            case "qwen": return "qwen-vl-max";
            case "doubao": return config.model;
            case "openai": return "gpt-4o";
            default: return "qwen-vl-max";
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

    private Long getPersonalModelId(Long userId) {
        try {
            String val = redisTemplate.opsForValue().get("personal_model:" + userId);
            if (val != null && !val.isBlank()) {
                return Long.parseLong(val);
            }
        } catch (Exception ex) {
            log.warn("[WARN] Redis read personal_model failed: {}", ex.getMessage());
        }
        return null;
    }

    private void savePersonalModelId(Long userId, Long modelId) {
        try {
            redisTemplate.opsForValue().set("personal_model:" + userId, String.valueOf(modelId), Duration.ofDays(365));
            log.debug("[DEBUG] Personal model bound: userId={} modelId={}", userId, modelId);
        } catch (Exception ex) {
            log.warn("[WARN] Redis write personal_model failed: {}", ex.getMessage());
        }
    }

    private String trySwitchModel(Long userId, String question, List<ModelConfig> allConfigs) {
        String q = question.trim().toLowerCase();
        if (!q.contains("切换") && !q.contains("换") && !q.contains("改用")) {
            return null;
        }

        ModelConfig target = null;
        for (ModelConfig config : allConfigs) {
            String provider = config.provider != null ? config.provider.toLowerCase() : "";
            switch (provider) {
                case "doubao":
                    if (q.contains("豆包") || q.contains("doubao")) {
                        target = config;
                    }
                    break;
                case "qwen":
                    if (q.contains("千问") || q.contains("qwen") || q.contains("通义")) {
                        target = config;
                    }
                    break;
                case "deepseek":
                    if (q.contains("deepseek") || q.contains("深度求索")) {
                        target = config;
                    }
                    break;
                case "zhipu":
                    if (q.contains("智谱") || q.contains("zhipu") || q.contains("glm")) {
                        target = config;
                    }
                    break;
            }
            if (target != null) break;
        }

        if (target != null) {
            savePersonalModelId(userId, target.id);
            String displayName;
            switch (target.provider.toLowerCase()) {
                case "doubao": displayName = "豆包"; break;
                case "qwen": displayName = "千问"; break;
                case "deepseek": displayName = "DeepSeek"; break;
                case "zhipu": displayName = "智谱 GLM"; break;
                default: displayName = target.provider; break;
            }
            log.debug("[DEBUG] Model switch: userId={} -> {} (id={})", userId, displayName, target.id);
            String msg = "✅ 已成功切换为「" + displayName + "」模型，后续所有问题都将由该模型回答。";
            return "{\"answer\":\"" + msg.replace("\"", "\\\"") + "\"}";
        }

        return null;
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
