package com.example.chat.router;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模型路由器
 * 根据 TaskType 从 model_configs 表中选择得分最高的启用模型
 *
 * 开关：app.router.enabled=true 开启
 */
@Service
@ConditionalOnProperty(name = "app.router.enabled", havingValue = "true")
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ModelConfigRepository modelConfigRepository;

    public ModelRouter(ModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    /**
     * 根据任务类型路由到最优模型
     *
     * @param taskType          任务类型
     * @param scene             业务场景（仅用于日志）
     * @param preferredModelId  用户指定的偏好模型 ID（可为 null）
     * @return RoutingDecision，包含选中的 ModelConfig 信息
     */
    public RoutingDecision route(TaskType taskType, String scene, Long preferredModelId) {
        List<ModelConfig> enabled = loadEnabledModels();

        // a. 用户指定了 preferredModelId 且匹配任务类型，直接用
        if (preferredModelId != null) {
            ModelConfig preferred = findById(enabled, preferredModelId);
            if (preferred != null && matchesTaskType(preferred, taskType)) {
                RoutingDecision decision = new RoutingDecision(taskType, preferred,
                        "用户指定模型且匹配任务类型 " + taskType);
                log.info("[Router] scene={} taskType={} -> userPreferred id={} model={}",
                        scene, taskType, preferredModelId, preferred.model);
                return decision;
            }
            // 指定了但不匹配：记录但仍尝试按得分路由
            log.info("[Router] 用户指定模型 id={} 不匹配任务类型 {}，按得分路由",
                    preferredModelId, taskType);
        }

        // b. 按任务类型打分排序
        List<ScoredModel> scored = scoreAll(enabled, taskType);
        if (!scored.isEmpty()) {
            ModelConfig best = scored.get(0).config;
            RoutingDecision decision = new RoutingDecision(taskType, best,
                    "按 " + taskType + " 得分路由，最高分=" + scored.get(0).score);
            for (ScoredModel sm : scored) {
                if (!sm.config.equals(best)) {
                    decision.addAlternative(sm.config.model);
                }
            }
            log.info("[Router] scene={} taskType={} -> scored best model={} provider={} score={}",
                    scene, taskType, best.model, best.provider, scored.get(0).score);
            return decision;
        }

        // c. 找不到匹配的，返回默认模型（第一个启用的）
        if (!enabled.isEmpty()) {
            ModelConfig fallback = enabled.get(0);
            RoutingDecision decision = new RoutingDecision(taskType, fallback,
                    "无匹配任务类型的模型，回退到默认启用模型");
            log.warn("[Router] scene={} taskType={} -> 无匹配模型，回退到 {}",
                    scene, taskType, fallback.model);
            return decision;
        }

        // d. 完全没有启用模型：返回 null，由调用方兜底
        log.error("[Router] scene={} taskType={} -> 没有任何启用的模型", scene, taskType);
        RoutingDecision empty = new RoutingDecision();
        empty.taskType = taskType;
        empty.reason = "无可用模型";
        return empty;
    }

    /**
     * 加载启用的模型列表（按 priority 升序）
     */
    private List<ModelConfig> loadEnabledModels() {
        try {
            List<ModelConfig> enabled = modelConfigRepository.findAllEnabled();
            if (enabled == null || enabled.isEmpty()) {
                return new ArrayList<>();
            }
            enabled.sort(Comparator.comparingInt(c -> c.priority != null ? c.priority : 100));
            return enabled;
        } catch (Exception ex) {
            log.warn("[Router] 加载启用模型失败: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 从列表中按 ID 查找模型
     */
    private ModelConfig findById(List<ModelConfig> configs, Long id) {
        if (id == null) return null;
        for (ModelConfig c : configs) {
            if (c.id != null && c.id.equals(id)) return c;
        }
        return null;
    }

    /**
     * 判断模型是否匹配任务类型
     * - VISION 任务：需 modelType=image_parse 或模型名含 vl/vision
     * - 其他任务：默认匹配（只要不是 image/video 生成类型）
     */
    private boolean matchesTaskType(ModelConfig config, TaskType taskType) {
        if (config == null) return false;
        String modelType = config.modelType;
        String modelName = config.model != null ? config.model.toLowerCase() : "";

        if (taskType == TaskType.VISION) {
            return "image_parse".equals(modelType)
                    || modelName.contains("vl")
                    || modelName.contains("vision");
        }
        // 非视觉任务排除图像/视频生成模型
        if ("image".equals(modelType) || "video".equals(modelType)) {
            return false;
        }
        return true;
    }

    /**
     * 对所有启用模型按任务类型打分，返回降序排列的列表
     */
    private List<ScoredModel> scoreAll(List<ModelConfig> configs, TaskType taskType) {
        List<ScoredModel> scored = new ArrayList<>();
        for (ModelConfig c : configs) {
            if (!matchesTaskType(c, taskType)) continue;
            int score = score(c, taskType);
            if (score > 0) {
                scored.add(new ScoredModel(c, score));
            }
        }
        // 得分降序，得分相同则按 priority 升序
        scored.sort((a, b) -> {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            int pa = a.config.priority != null ? a.config.priority : 100;
            int pb = b.config.priority != null ? b.config.priority : 100;
            return Integer.compare(pa, pb);
        });
        return scored;
    }

    /**
     * 根据任务类型和模型名称关键词打分
     * 评分规则按需求文档定义
     */
    private int score(ModelConfig config, TaskType taskType) {
        String model = config.model != null ? config.model.toLowerCase() : "";
        String provider = config.provider != null ? config.provider.toLowerCase() : "";

        switch (taskType) {
            case VISION:
                // qwen-vl > qwen-vision > 其他
                if (model.contains("qwen-vl") || model.contains("qwen-vision")) {
                    if (model.contains("qwen-vl")) return 100;
                    return 90;
                }
                // image_parse 类型也支持
                if ("image_parse".equals(config.modelType)) return 80;
                // 其他视觉能力模型
                if (model.contains("vl") || model.contains("vision")) return 50;
                return 0;

            case SUMMARIZATION:
                // qwen-turbo > qwen-plus > deepseek > qwen-max
                if (model.contains("qwen-turbo")) return 100;
                if (model.contains("qwen-plus")) return 90;
                if (provider.contains("deepseek")) return 80;
                if (model.contains("qwen-max")) return 70;
                // 通用 chat 模型给基础分
                return 30;

            case EMOTIONAL:
                // doubao-seed-character > qwen-plus > 其他
                if (model.contains("doubao-seed-character") || model.contains("character")) return 100;
                if (model.contains("qwen-plus")) return 90;
                if (provider.contains("doubao")) return 70;
                return 30;

            case COMPLEX_REASONING:
                // qwen-max > deepseek > qwen-plus
                if (model.contains("qwen-max")) return 100;
                if (provider.contains("deepseek")) return 90;
                if (model.contains("qwen-plus")) return 80;
                return 30;

            case CODE:
                // deepseek > qwen-coder > qwen-plus
                if (provider.contains("deepseek")) return 100;
                if (model.contains("qwen-coder") || model.contains("coder")) return 90;
                if (model.contains("qwen-plus")) return 80;
                return 30;

            case CREATIVE:
                // qwen-max > doubao > qwen-plus
                if (model.contains("qwen-max")) return 100;
                if (provider.contains("doubao")) return 90;
                if (model.contains("qwen-plus")) return 80;
                return 30;

            case SIMPLE_CHAT:
                // qwen-plus > qwen-turbo > deepseek
                if (model.contains("qwen-plus")) return 100;
                if (model.contains("qwen-turbo")) return 90;
                if (provider.contains("deepseek")) return 80;
                if (provider.contains("doubao")) return 70;
                return 30;

            case DEBATE:
                // qwen-max > deepseek > qwen-plus
                if (model.contains("qwen-max")) return 100;
                if (provider.contains("deepseek")) return 90;
                if (model.contains("qwen-plus")) return 80;
                return 30;

            default:
                return 30;
        }
    }

    /**
     * 带分数的模型包装
     */
    private static class ScoredModel {
        final ModelConfig config;
        final int score;

        ScoredModel(ModelConfig config, int score) {
            this.config = config;
            this.score = score;
        }
    }
}
