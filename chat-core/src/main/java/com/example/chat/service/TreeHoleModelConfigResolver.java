package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.ModelNotAvailableException;
import com.example.chat.repository.ModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 树洞模型配置解析器
 * 统一管理树洞模块的模型配置读取与兜底逻辑
 */
@Service
public class TreeHoleModelConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(TreeHoleModelConfigResolver.class);

    private final ModelConfigRepository modelConfigRepository;
    private final LlmConfigProperties llmConfig;

    public TreeHoleModelConfigResolver(ModelConfigRepository modelConfigRepository,
                                        LlmConfigProperties llmConfig) {
        this.modelConfigRepository = modelConfigRepository;
        this.llmConfig = llmConfig;
    }

    /** 固定使用 model_configs 中 id=2 的千问模型（树洞主模型），带兜底 */
    public ModelConfig resolveMainModel() {
        try {
            ModelConfig config = modelConfigRepository.findById(2L);
            if (config != null) return config;
        } catch (Exception e) {
            log.warn("无法读取 model_configs id=2，使用默认配置: {}", e.getMessage());
        }
        ModelConfig fallback = new ModelConfig();
        fallback.provider = "qwen";
        fallback.model = llmConfig.getModel();
        fallback.apiKeyEncrypted = llmConfig.getApiKey();
        return fallback;
    }

    /** 固定使用 text_parse 类型的智谱模型（glm-4.6v-flash, id=9）做文件解析 */
    public ModelConfig resolveZhipuOrThrow() {
        ModelConfig config = resolveZhipuConfig();
        if (config == null) throw new ModelNotAvailableException("智谱", "zhipu");
        return config;
    }

    private ModelConfig resolveZhipuConfig() {
        try {
            ModelConfig primary = modelConfigRepository.findById(9L);
            if (primary != null && "text_parse".equals(primary.modelType)
                    && "zhipu".equalsIgnoreCase(primary.provider)
                    && Boolean.TRUE.equals(primary.enabled)) {
                return primary;
            }
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
    public ModelConfig resolveImageParseOrThrow() {
        ModelConfig config = resolveImageParseConfig();
        if (config == null) throw new ModelNotAvailableException("图片解析", "image-parser");
        return config;
    }

    private ModelConfig resolveImageParseConfig() {
        try {
            ModelConfig config = modelConfigRepository.findById(8L);
            if (config != null && "image_parse".equals(config.modelType)) return config;
            return modelConfigRepository.findAllEnabledByType("image_parse")
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("无法读取 image_parse 模型配置: {}", e.getMessage());
            return null;
        }
    }
}
