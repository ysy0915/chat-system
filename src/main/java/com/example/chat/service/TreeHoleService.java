package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.repository.TreeHoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final LLMInvoker llmInvoker;

    public TreeHoleService(TreeHoleRepository treeHoleRepository,
                           ModelConfigRepository modelConfigRepository,
                           RateLimitService rateLimitService,
                           ObjectMapper objectMapper,
                           SimpMessagingTemplate messagingTemplate,
                           LLMInvoker llmInvoker) {
        this.treeHoleRepository = treeHoleRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.llmInvoker = llmInvoker;
    }

    /** 固定使用 model_configs 中 id=2 的千问模型配置（树洞主模型） */
    private ModelConfig resolveModelConfig() {
        try {
            ModelConfig config = modelConfigRepository.findById(2L);
            if (config != null) return config;
        } catch (Exception e) {
            log.warn("无法读取 model_configs id=2，使用默认配置: {}", e.getMessage());
        }
        ModelConfig fallback = new ModelConfig();
        fallback.provider = "qwen";
        fallback.model = defaultModel;
        fallback.apiKeyEncrypted = defaultApiKey;
        return fallback;
    }

    /** 固定使用 text_parse 类型的智谱模型（glm-4.6v-flash, id=9）做文件解析 / 文档生成 */
    private ModelConfig resolveZhipuConfig() {
        try {
            // 优先取 id=9
            ModelConfig primary = modelConfigRepository.findById(9L);
            if (primary != null && "text_parse".equals(primary.modelType)
                    && "zhipu".equalsIgnoreCase(primary.provider)
                    && Boolean.TRUE.equals(primary.enabled)) {
                return primary;
            }
            // 兜底：按 model_type=text_parse + provider=zhipu 筛选
            return modelConfigRepository.findAllEnabledByType("text_parse")
                    .stream()
                    .filter(c -> "zhipu".equalsIgnoreCase(c.provider))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("无法读取智谱 text_parse 模型配置: {}", e.getMessage());
            return null;
        }
    }

    /** 固定使用 id=8 的 image_parse 模型做图片解析 */
    private ModelConfig resolveImageParseConfig() {
        try {
            ModelConfig config = modelConfigRepository.findById(8L);
            if (config != null && "image_parse".equals(config.modelType)) return config;
            // 兜底：按 model_type=image_parse 筛选
            return modelConfigRepository.findAllEnabledByType("image_parse")
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("无法读取 image_parse 模型配置: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 带文件的树洞请求：
     *   - 图片文件 → id=8 qwen-vl-max 视觉解析
     *   - 其他文件 → 智谱文本解析
     */
    public TreeHoleMessage askWithFile(Long userId, String question, String mood,
                                       String fileName, byte[] fileBytes) {
        if (!rateLimitService.isAllowed(userId)) {
            long retry = rateLimitService.getRemainingSeconds(userId);
            throw new RuntimeException("发送太频繁，请 " + retry + " 秒后再试");
        }

        String lowerName = fileName != null ? fileName.toLowerCase() : "";
        boolean isImage = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".webp");

        // 保存记录
        TreeHoleMessage m = new TreeHoleMessage();
        m.reqId = UUID.randomUUID().toString();
        m.userId = userId;
        m.question = (question != null ? question : "") + (fileName != null ? " 📎 " + fileName : "");
        m.mood = mood;
        m.status = "pending";
        treeHoleRepository.insert(m);

        try {
            String answer;
            if (isImage) {
                answer = handleImageFile(question, mood, fileName, fileBytes);
            } else {
                answer = handleTextFile(question, mood, fileName, fileBytes);
            }
            m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            m.status = "done";
        } catch (Exception e) {
            log.error("TreeHole 文件解析失败: {}", e.getMessage());
            Throwable root = e.getCause() != null ? e.getCause() : e;
            String errMsg = root.getMessage() != null ? root.getMessage() : e.getMessage();
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", "解析失败：" + errMsg));
            } catch (Exception je) {
                m.answerJson = "{\"answer\":\"解析失败：未知错误\"}";
            }
            m.status = "error";
        }

        treeHoleRepository.updateByReqId(m);
        return m;
    }

    /** 图片文件：用 qwen-vl-max 多模态解析 */
    private String handleImageFile(String question, String mood, String fileName, byte[] fileBytes) throws Exception {
        ModelConfig config = resolveImageParseConfig();
        if (config == null) throw new RuntimeException("图片解析模型未配置（id=8）");

        // 推断 mimeType
        String lowerName = fileName != null ? fileName.toLowerCase() : "";
        String mimeType = lowerName.endsWith(".png") ? "image/png"
                : lowerName.endsWith(".gif") ? "image/gif"
                : lowerName.endsWith(".webp") ? "image/webp"
                : "image/jpeg";

        String fileBase64 = Base64.getEncoder().encodeToString(fileBytes);
        String imageQuestion = (question != null && !question.isBlank()) ? question : "请描述这张图片的内容";
        if (mood != null && !mood.isBlank()) {
            imageQuestion = "[情绪：" + mood + "] " + imageQuestion;
        }

        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", imageQuestion));
        contentParts.add(Map.of("type", "image_url", "image_url",
                Map.of("url", "data:" + mimeType + ";base64," + fileBase64)));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT + "\n\n如果用户上传了图片，请结合图片内容给予温暖的情感陪伴与回应。"));
        messages.add(Map.of("role", "user", "content", contentParts));

        log.info("TreeHole 图片解析 model={}", config.model);
        return llmInvoker.invoke(config, messages, 0.85, "treehole", defaultBaseUrl, defaultApiKey);
    }

    /** 非图片文件：提取文本内容，用智谱解析 */
    private String handleTextFile(String question, String mood, String fileName, byte[] fileBytes) throws Exception {
        ModelConfig zhipu = resolveZhipuConfig();
        if (zhipu == null) throw new RuntimeException("智谱模型未配置，请在模型管理中启用智谱");

        String fileText = "";
        String lowerName = fileName != null ? fileName.toLowerCase() : "";
        if (fileBytes != null && fileBytes.length > 0) {
            if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") ||
                lowerName.endsWith(".csv") || lowerName.endsWith(".json") ||
                lowerName.endsWith(".log") || lowerName.endsWith(".xml")) {
                fileText = new String(fileBytes, StandardCharsets.UTF_8);
                if (fileText.length() > 8000) fileText = fileText.substring(0, 8000) + "\n...(内容已截断)";
            } else {
                fileText = "[已上传文件: " + fileName + "，共 " + fileBytes.length / 1024 + " KB]";
            }
        }

        String lowerQ = question != null ? question.toLowerCase() : "";
        boolean genDoc = lowerQ.contains("生成文档") || lowerQ.contains("生成word") || lowerQ.contains("生成报告");
        boolean genPpt = lowerQ.contains("生成ppt") || lowerQ.contains("生成幻灯片") || lowerQ.contains("做ppt");

        String systemPrompt;
        if (genPpt) {
            systemPrompt = "你是专业PPT内容策划师。请根据提供的材料，生成一份结构完整的PPT大纲，" +
                    "包含标题页、目录、各章节要点（每页3~5条），以及结尾页。用Markdown格式输出，" +
                    "每个章节用 ## 标注，每条要点用 - 开头。结尾加「供您参考」。";
        } else if (genDoc) {
            systemPrompt = "你是专业文档撰写助手。请根据提供的材料，生成一份结构完整、逻辑清晰的文档，" +
                    "包含标题、摘要、正文各节（用 ## 标注）以及结语。结尾加「供您参考」。";
        } else {
            systemPrompt = SYSTEM_PROMPT + "\n\n请结合用户上传的文件内容进行温暖的情感陪伴与回应。";
        }

        String userContent = question != null && !question.isBlank() ? question : "请解析这份文件";
        if (!fileText.isBlank()) userContent += "\n\n【文件内容】\n" + fileText;
        if (mood != null && !mood.isBlank()) userContent = "[情绪：" + mood + "] " + userContent;

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userContent));

        return llmInvoker.invoke(zhipu, messages, 0.85, "treehole", defaultBaseUrl, defaultApiKey);
    }

    /** 获取当前用户的树洞历史（最多50条，独立数据表） */
    public List<TreeHoleMessage> getHistory(Long userId) {
        return treeHoleRepository.findByUserId(userId);
    }

    /**
     * 流式版本：发送情绪内容，逐 token 通过 WebSocket 推送给前端
     * 推送 topic: /topic/treehole.{userId}
     * 消息类型: stream_start / stream_token / done / error
     */
    public void askAndStream(Long userId, String question, String mood) {
        log.info("TreeHole askAndStream 被调用, userId={}, question={}", userId, question);
        if (!rateLimitService.isAllowed(userId)) {
            long retry = rateLimitService.getRemainingSeconds(userId);
            messagingTemplate.convertAndSend("/topic/treehole." + userId,
                    Map.of("type", "error", "message", "发送太频繁，请 " + retry + " 秒后再试"));
            return;
        }

        // 构建历史上下文
        List<TreeHoleMessage> recent = treeHoleRepository.findRecentByUserId(userId);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        int start = Math.max(0, recent.size() - 10);
        for (int i = start; i < recent.size(); i++) {
            TreeHoleMessage prev = recent.get(i);
            messages.add(Map.of("role", "user", "content", prev.question));
            if (prev.answerJson != null && !prev.answerJson.isBlank()) {
                messages.add(Map.of("role", "assistant", "content", prev.answerJson));
            }
        }
        String fullQuestion = (mood != null && !mood.isBlank())
                ? "[情绪：" + mood + "] " + question : question;
        messages.add(Map.of("role", "user", "content", fullQuestion));

        // 保存记录
        TreeHoleMessage m = new TreeHoleMessage();
        m.reqId = UUID.randomUUID().toString();
        m.userId = userId;
        m.question = question;
        m.mood = mood;
        m.status = "pending";
        treeHoleRepository.insert(m);

        ModelConfig config = resolveModelConfig();
        String effectiveApiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        final String reqId = m.reqId;
        final Long fUserId = userId;

        // 通知前端开始流式输出
        log.info("TreeHole 推送 stream_start, topic=/topic/treehole.{}", fUserId);
        messagingTemplate.convertAndSend("/topic/treehole." + fUserId,
                Map.of("type", "stream_start", "req_id", reqId));

        // 异步调用流式 API
        new Thread(() -> {
            try {
                String answer = llmInvoker.invokeStream(config, messages, 0.85, "treehole",
                        defaultBaseUrl, effectiveApiKey,
                        token -> {
                            messagingTemplate.convertAndSend("/topic/treehole." + fUserId,
                                    Map.of("type", "stream_token", "req_id", reqId, "token", token));
                        });

                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
                m.status = "done";
                treeHoleRepository.updateByReqId(m);

                messagingTemplate.convertAndSend("/topic/treehole." + fUserId,
                        Map.of("type", "done", "req_id", reqId, "answer", answer));
            } catch (Exception e) {
                log.error("TreeHole 流式调用失败: {}", e.getMessage(), e);
                m.answerJson = "{\"answer\":\"树洞暂时出了点小问题，请稍后再试...\"}";
                m.status = "error";
                treeHoleRepository.updateByReqId(m);
                messagingTemplate.convertAndSend("/topic/treehole." + fUserId,
                        Map.of("type", "error", "req_id", reqId, "message", "生成失败: " + e.getMessage()));
            }
        }).start();
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

        // 解析模型配置（从数据库读取）
        ModelConfig config = resolveModelConfig();

        // 调用 AI（通过 LLMInvoker 统一入口）
        try {
            String answer = llmInvoker.invoke(config, messages, 0.85, "treehole", defaultBaseUrl, defaultApiKey);
            m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            m.status = "done";
        } catch (Exception e) {
            log.error("TreeHole AI 调用失败: {}", e.getMessage());
            m.answerJson = "{\"answer\":\"树洞暂时出了点小问题，请稍后再试...\"}";
            m.status = "error";
        }

        treeHoleRepository.updateByReqId(m);
        return m;
    }
}
