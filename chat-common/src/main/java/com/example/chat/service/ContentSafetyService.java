package com.example.chat.service;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.teaopenapi.models.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
@ConditionalOnClass(name = "com.aliyun.green20220302.Client")
public class ContentSafetyService implements ContentSafetyProvider {

    private static final Logger log = LoggerFactory.getLogger(ContentSafetyService.class);

    @Value("${content-safety.enabled:true}")
    private boolean enabled;

    @Value("${content-safety.access-key-id:}")
    private String accessKeyId;

    @Value("${content-safety.access-key-secret:}")
    private String accessKeySecret;

    @Value("${content-safety.endpoint:green-cip.cn-beijing.aliyuncs.com}")
    private String endpoint;

    @Value("${content-safety.region-id:cn-beijing}")
    private String regionId;

    /** 本地敏感词库（逗号分隔，命中即拦截，避免高并发下同步调阿里云 API） */
    @Value("${content-safety.local-blacklist:}")
    private String localBlacklist;

    private Client client;
    private boolean clientReady;
    private volatile java.util.List<String> localBlacklistWords = java.util.Collections.emptyList();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    public void init() {
        loadLocalBlacklist();
        if (!enabled) {
            log.info("[ContentSafety] 内容安全服务已禁用");
            return;
        }
        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            log.warn("[ContentSafety] WARN: AccessKey 未配置，内容安全检测将跳过");
            return;
        }
        try {
            Config config = new Config();
            config.accessKeyId = accessKeyId;
            config.accessKeySecret = accessKeySecret;
            config.endpoint = endpoint;
            config.regionId = regionId;
            this.client = new Client(config);
            this.clientReady = true;
            log.info("[ContentSafety] 阿里云内容安全服务初始化成功, endpoint={}", endpoint);
        } catch (Exception e) {
            log.error("[ContentSafety] 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 加载本地敏感词库（逗号分隔）。支持启动时加载，后续可经 {@link #reloadLocalBlacklist(String)} 热更新。
     */
    private void loadLocalBlacklist() {
        if (localBlacklist == null || localBlacklist.isBlank()) {
            localBlacklistWords = java.util.Collections.emptyList();
            log.info("[ContentSafety] 本地敏感词库未配置（空）");
            return;
        }
        java.util.List<String> words = java.util.Arrays.stream(localBlacklist.split(","))
                .map(String::trim)
                .filter(w -> !w.isEmpty())
                .toList();
        // 原子替换引用，保证并发读安全
        localBlacklistWords = words;
        log.info("[ContentSafety] 本地敏感词库加载完成，共 {} 个词", words.size());
    }

    /**
     * 热更新本地敏感词库（运维/管理面调用）。
     */
    public void reloadLocalBlacklist(String newBlacklist) {
        this.localBlacklist = newBlacklist;
        loadLocalBlacklist();
    }

    /**
     * 本地敏感词预检：微秒级、无网络，命中直接返回标签（加速拦截）。
     *
     * @return LOCAL_BLACKLIST_LABEL=命中；null=未命中（需走阿里云 API 完整检测）
     */
    private String checkLocalBlacklist(String text) {
        if (localBlacklistWords.isEmpty() || text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        for (String word : localBlacklistWords) {
            if (lower.contains(word.toLowerCase())) {
                log.warn("[ContentSafety] ❌ 本地词库命中: word={}, text={}", word,
                        (text.length() > 50 ? text.substring(0, 50) + "..." : text));
                return LOCAL_BLACKLIST_LABEL;
            }
        }
        return null;
    }

    /**
     * 检测文本是否包含敏感内容
     * @return null=安全通过, 非null=命中的敏感标签(如 politics, pornography, violence 等)
     */
    @Override
    public String detectSensitive(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 第一层：本地敏感词预检（微秒级、无网络、不依赖阿里云 client），
        // 命中直接拦截——即使阿里云服务禁用/未就绪，本地词库仍生效（安全第一）。
        String localHit = checkLocalBlacklist(text);
        if (localHit != null) {
            return localHit;
        }
        // 严格 fail-close：本地词库未命中，但阿里云服务不可用（禁用/未就绪/无 AK）时，
        // 拒绝放行——宁可牺牲可用性也不让消息绕过语义检测（安全第一）。
        if (!enabled || !clientReady) {
            log.warn("[ContentSafety] ❌ 阿里云检测不可用，fail-close 拒绝放行: enabled={}, clientReady={}, text={}",
                    enabled, clientReady, (text.length() > 50 ? text.substring(0, 50) + "..." : text));
            return ERROR_LABEL;
        }
        try {
            String preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
            log.info("[ContentSafety] 开始检测, text={}", preview);

            TextModerationRequest request = new TextModerationRequest();
            request.setService("chat_detection");
            String params = objectMapper.writeValueAsString(java.util.Map.of("content", text));
            request.setServiceParameters(params);

            TextModerationResponse response = client.textModeration(request);
            TextModerationResponseBody body = response.getBody();

            if (body != null && body.getData() != null) {
                String labels = body.getData().getLabels();
                if (labels != null && !labels.isEmpty() && !"nonLabel".equals(labels)) {
                    log.warn("[ContentSafety] ❌ 拦截: labels={}, text={}", labels, preview);
                    return labels;
                }
                log.info("[ContentSafety] ✅ 通过: labels={}, text={}", labels, preview);
            } else {
                log.info("[ContentSafety] ✅ 通过: 无返回数据, text={}", preview);
            }
            return null;
        } catch (Exception e) {
            // fail-close：检测服务异常时不放行，返回错误标签由调用方拒绝（避免敏感内容绕过检测）
            log.error("[ContentSafety] ❌ 检测异常，拒绝放行(fail-close): {}, text={}",
                    e.getMessage(), (text.length() > 50 ? text.substring(0, 50) + "..." : text));
            return ERROR_LABEL;
        }
    }

    /**
     * 将标签转为友好的中文提示
     */
    @Override
    public String getLabelHint(String labels) {
        if (labels == null) return "内容包含敏感信息";
        if (LOCAL_BLACKLIST_LABEL.equals(labels)) return "问题包含敏感内容，请修改后重试";
        if (ERROR_LABEL.equals(labels)) return "内容安全服务暂不可用，请稍后重试";
        if (labels.contains("politics")) return "问题涉及敏感政治内容，请修改后重试";
        if (labels.contains("pornography")) return "问题包含不适当内容，请修改后重试";
        if (labels.contains("violence")) return "问题包含暴力内容，请修改后重试";
        if (labels.contains("terror")) return "问题涉及敏感内容，请修改后重试";
        if (labels.contains("abuse")) return "问题包含不当言论，请修改后重试";
        if (labels.contains("contraband")) return "问题涉及违禁内容，请修改后重试";
        return "问题包含敏感内容，请修改后重试";
    }
}
