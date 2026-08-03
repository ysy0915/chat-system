package com.example.chat.service;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.teaopenapi.models.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class ContentSafetyService {

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

    private Client client;
    private boolean clientReady = false;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    public void init() {
        if (!enabled) {
            System.out.println("[ContentSafety] 内容安全服务已禁用");
            return;
        }
        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            System.out.println("[ContentSafety] WARN: AccessKey 未配置，内容安全检测将跳过");
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
            System.out.println("[ContentSafety] 阿里云内容安全服务初始化成功, endpoint=" + endpoint);
        } catch (Exception e) {
            System.err.println("[ContentSafety] 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 检测文本是否包含敏感内容
     * @return null=安全通过, 非null=命中的敏感标签(如 politics, pornography, violence 等)
     */
    public String detectSensitive(String text) {
        if (!enabled || !clientReady || text == null || text.isBlank()) {
            System.out.println("[ContentSafety] 跳过检测: enabled=" + enabled + ", clientReady=" + clientReady + ", textEmpty=" + (text == null || text.isBlank()));
            return null;
        }
        try {
            String preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
            System.out.println("[ContentSafety] 开始检测, text=" + preview);

            TextModerationRequest request = new TextModerationRequest();
            request.setService("chat_detection");
            String params = objectMapper.writeValueAsString(java.util.Map.of("content", text));
            request.setServiceParameters(params);

            TextModerationResponse response = client.textModeration(request);
            TextModerationResponseBody body = response.getBody();

            if (body != null && body.getData() != null) {
                String labels = body.getData().getLabels();
                if (labels != null && !labels.isEmpty() && !"nonLabel".equals(labels)) {
                    System.out.println("[ContentSafety] ❌ 拦截: labels=" + labels + ", text=" + preview);
                    return labels;
                }
                System.out.println("[ContentSafety] ✅ 通过: labels=" + labels + ", text=" + preview);
            } else {
                System.out.println("[ContentSafety] ✅ 通过: 无返回数据, text=" + preview);
            }
            return null;
        } catch (Exception e) {
            System.err.println("[ContentSafety] ⚠️ 异常放行: " + e.getMessage() + ", text=" + (text.length() > 50 ? text.substring(0, 50) + "..." : text));
            return null;
        }
    }

    /**
     * 将标签转为友好的中文提示
     */
    public String getLabelHint(String labels) {
        if (labels == null) return "内容包含敏感信息";
        if (labels.contains("politics")) return "问题涉及敏感政治内容，请修改后重试";
        if (labels.contains("pornography")) return "问题包含不适当内容，请修改后重试";
        if (labels.contains("violence")) return "问题包含暴力内容，请修改后重试";
        if (labels.contains("terror")) return "问题涉及敏感内容，请修改后重试";
        if (labels.contains("abuse")) return "问题包含不当言论，请修改后重试";
        if (labels.contains("contraband")) return "问题涉及违禁内容，请修改后重试";
        return "问题包含敏感内容，请修改后重试";
    }
}
