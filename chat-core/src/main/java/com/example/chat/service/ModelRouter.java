package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Comparator;
import java.util.List;

/**
 * 模型路由器 — 负责智能路由、模型切换、个人模型绑定管理。
 */
@Service
public class ModelRouter {
    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private final ModelConfigRepository modelConfigRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public ModelRouter(ModelConfigRepository modelConfigRepository,
                       RedisTemplate<String, String> redisTemplate) {
        this.modelConfigRepository = modelConfigRepository;
        this.redisTemplate = redisTemplate;
    }

    /** 加载所有启用的 chat 模型，按优先级排序。若无可用模型，返回只有一个降级配置的列表。 */
    public List<ModelConfig> loadChatModels(String defaultProvider, String defaultModel, String defaultApiKey) {
        List<ModelConfig> configs = modelConfigRepository.findAllEnabled().stream()
                .filter(c -> c.modelType == null || "chat".equalsIgnoreCase(c.modelType))
                .sorted(Comparator.comparingInt(c -> c.priority != null ? c.priority : 100))
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
        return configs;
    }

    /**
     * 智能路由：根据问题特征选择最优模型 provider。
     * - DeepSeek: 代码/算法/逻辑推理/数学/技术问题
     * - 千问: 创意写作/诗歌/故事/角色扮演
     * - 豆包: 日常对话/闲聊/通用问题（默认）
     */
    public String selectBestProvider(String question) {
        if (question == null || question.isBlank()) return "doubao";
        String q = question.toLowerCase(Locale.ROOT);

        for (String kw : new String[]{"代码", "编程", "算法", "bug", "error", "java", "python", "javascript",
                "sql", "逻辑", "推理", "数学", "计算", "技术", "架构", "接口", "函数", "正则", "复杂"}) {
            if (q.contains(kw)) return "deepseek";
        }
        for (String kw : new String[]{"写诗", "写一篇", "创作", "故事", "小说", "诗歌", "散文", "作文",
                "角色扮演", "续写", "创意", "文案", "广告语", "标语", "对联"}) {
            if (q.contains(kw)) return "qwen";
        }
        return "doubao";
    }

    /** 根据用户问题选择模型配置列表（群聊路由 / 私聊默认 / 绑定模型） */
    public List<ModelConfig> selectForChat(boolean isPrivate, Long userId, String question,
                                            List<ModelConfig> allConfigs) {
        Long boundModelId = getPersonalModelId(userId);

        if (isPrivate && boundModelId == null) {
            // 私聊无绑定 → DeepSeek
            List<ModelConfig> ds = allConfigs.stream()
                    .filter(c -> "deepseek".equalsIgnoreCase(c.provider)).toList();
            return ds.isEmpty() ? allConfigs : ds;
        } else if (boundModelId != null) {
            // 私人有绑定
            List<ModelConfig> bound = allConfigs.stream()
                    .filter(c -> c.id != null && c.id.equals(boundModelId)).toList();
            if (bound.isEmpty()) {
                List<ModelConfig> ds = allConfigs.stream()
                        .filter(c -> "deepseek".equalsIgnoreCase(c.provider)).toList();
                bound = ds.isEmpty() ? allConfigs : ds;
                log.warn("[WARN] 用户 {} 绑定的模型ID={} 不在可用chat模型中，回退", userId, boundModelId);
            }
            return bound;
        } else {
            // 群聊智能路由
            String best = selectBestProvider(question);
            List<ModelConfig> preferred = allConfigs.stream()
                    .filter(c -> best.equalsIgnoreCase(c.provider)).toList();
            log.info("[INFO] 群聊智能路由: question='{}' -> provider={}",
                    question.length() > 30 ? question.substring(0, 30) + "..." : question,
                    preferred.isEmpty() ? "fallback" : preferred.get(0).provider);
            return preferred.isEmpty() ? allConfigs : preferred;
        }
    }

    /** 尝试切换个人模型。命中返回切换成功的 JSON，未命中返回 null。 */
    public String trySwitch(Long userId, String question, List<ModelConfig> allConfigs) {
        String q = question.trim().toLowerCase(Locale.ROOT);
        if (!q.contains("切换") && !q.contains("换") && !q.contains("改用")) {
            return null;
        }
        ModelConfig target = findTargetModel(q, allConfigs);
        if (target == null) return null;

        savePersonalModelId(userId, target.id);
        String displayName = toDisplayName(target.provider);
        String msg = "✅ 已成功切换为「" + displayName + "」模型，后续所有问题都将由该模型回答。";
        return "{\"answer\":\"" + msg.replace("\"", "\\\"") + "\"}";
    }

    // ---------- 内部方法 ----------

    private ModelConfig findTargetModel(String query, List<ModelConfig> configs) {
        for (ModelConfig c : configs) {
            String p = c.provider != null ? c.provider.toLowerCase(Locale.ROOT) : "";
            switch (p) {
                case "doubao": if (query.contains("豆包") || query.contains("doubao")) return c; break;
                case "qwen":   if (query.contains("千问") || query.contains("qwen") || query.contains("通义")) return c; break;
                case "deepseek": if (query.contains("deepseek") || query.contains("深度求索")) return c; break;
                case "zhipu":  if (query.contains("智谱") || query.contains("zhipu") || query.contains("glm")) return c; break;
            }
        }
        return null;
    }

    public static String toDisplayName(String provider) {
        if (provider == null) return "未知";
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "doubao" -> "豆包";
            case "qwen" -> "千问";
            case "deepseek" -> "DeepSeek";
            case "zhipu" -> "智谱 GLM";
            case "ollama" -> "自研";
            case "moonshot" -> "Kimi";
            case "openai" -> "GPT";
            case "anthropic" -> "Claude";
            default -> provider;
        };
    }

    /** 模型展示名：provider 中文名 + 自研模型的模型名（如「自研 Hermes3」） */
    public static String modelDisplayName(String provider, String model) {
        String base = toDisplayName(provider);
        if (model == null || model.isBlank()) return base;
        return base + " " + model;
    }

    public Long getPersonalModelId(Long userId) {
        try {
            String val = redisTemplate.opsForValue().get("personal_model:" + userId);
            if (val != null && !val.isBlank()) return Long.parseLong(val);
        } catch (Exception ex) {
            log.warn("[WARN] Redis read personal_model failed: {}", ex.getMessage());
        }
        return null;
    }

    public void savePersonalModelId(Long userId, Long modelId) {
        try {
            redisTemplate.opsForValue().set("personal_model:" + userId, String.valueOf(modelId), Duration.ofDays(365));
        } catch (Exception ex) {
            log.warn("[WARN] Redis write personal_model failed: {}", ex.getMessage());
        }
    }
}
