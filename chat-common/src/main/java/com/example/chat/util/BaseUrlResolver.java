package com.example.chat.util;

import com.example.chat.entity.ModelConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 从 ModelConfig 中解析 baseUrl 的公共工具
 * 提取自 ChatProcessor / DebateProcessor / TreeHoleService / ModelAutoChatService / MediaGenController 中的重复代码
 */
@Component
public class BaseUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(BaseUrlResolver.class);

    private final ObjectMapper objectMapper;

    public BaseUrlResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析模型的 baseUrl
     * 优先从 metaJson 中读取（兼容 baseUrl 驼峰和 base_url 下划线），
     * 其次按 provider 匹配默认地址
     */
    public String resolve(ModelConfig config, String defaultBaseUrl) {
        if (config.metaJson != null && !config.metaJson.isBlank()) {
            try {
                Map<?, ?> meta = objectMapper.readValue(config.metaJson, Map.class);
                Object url = meta.get("baseUrl");
                if (url == null) url = meta.get("base_url");
                if (url != null) return url.toString();
            } catch (JsonProcessingException ignored) {
                log.debug("BaseUrlResolver 解析 metaJson 失败，使用默认地址");
            }
        }
        // 按 provider 匹配默认地址
        if (config.provider != null) {
            switch (config.provider.toLowerCase(Locale.ROOT)) {
                case "deepseek":
                    return "https://api.deepseek.com/v1";
                case "doubao":
                    return "https://ark.cn-beijing.volces.com/api/v3";
                case "qwen":
                default:
                    return defaultBaseUrl != null ? defaultBaseUrl : "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
        }
        return defaultBaseUrl != null ? defaultBaseUrl : "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }
}
