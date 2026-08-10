package com.example.chat.router;

import com.example.chat.entity.ModelConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由决策记录
 * 记录一次模型路由的完整决策信息，用于日志和监控
 */
public class RoutingDecision {

    /** 任务类型 */
    public TaskType taskType;
    /** 选中的模型名称 */
    public String selectedModel;
    /** 选中的模型 provider */
    public String selectedProvider;
    /** 选中的模型配置 ID */
    public Long selectedModelId;
    /** 选中的完整 ModelConfig（供调用方直接使用） */
    public ModelConfig selectedConfig;
    /** 路由原因说明 */
    public String reason;
    /** 候选模型列表（按得分降序，仅记录模型名） */
    public List<String> alternatives = new ArrayList<>();

    public RoutingDecision() {
    }

    public RoutingDecision(TaskType taskType, ModelConfig selected, String reason) {
        this.taskType = taskType;
        this.selectedConfig = selected;
        this.selectedModel = selected != null ? selected.model : null;
        this.selectedProvider = selected != null ? selected.provider : null;
        this.selectedModelId = selected != null ? selected.id : null;
        this.reason = reason;
    }

    public TaskType getTaskType() { return taskType; }
    public void setTaskType(TaskType taskType) { this.taskType = taskType; }

    public String getSelectedModel() { return selectedModel; }
    public void setSelectedModel(String selectedModel) { this.selectedModel = selectedModel; }

    public String getSelectedProvider() { return selectedProvider; }
    public void setSelectedProvider(String selectedProvider) { this.selectedProvider = selectedProvider; }

    public Long getSelectedModelId() { return selectedModelId; }
    public void setSelectedModelId(Long selectedModelId) { this.selectedModelId = selectedModelId; }

    public ModelConfig getSelectedConfig() { return selectedConfig; }
    public void setSelectedConfig(ModelConfig selectedConfig) { this.selectedConfig = selectedConfig; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<String> getAlternatives() { return alternatives; }
    public void setAlternatives(List<String> alternatives) { this.alternatives = alternatives; }

    /**
     * 添加候选模型
     */
    public void addAlternative(String modelName) {
        if (modelName != null && !alternatives.contains(modelName)) {
            alternatives.add(modelName);
        }
    }

    /**
     * 序列化为简单 JSON（避免引入 Jackson 依赖，便于日志输出）
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"taskType\":\"").append(taskType != null ? taskType.name() : "").append("\",");
        sb.append("\"selectedModel\":\"").append(escape(selectedModel)).append("\",");
        sb.append("\"selectedProvider\":\"").append(escape(selectedProvider)).append("\",");
        sb.append("\"selectedModelId\":").append(selectedModelId != null ? selectedModelId : "null").append(',');
        sb.append("\"reason\":\"").append(escape(reason)).append("\",");
        sb.append("\"alternatives\":[");
        for (int i = 0; i < alternatives.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(escape(alternatives.get(i))).append('\"');
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RoutingDecision{taskType=" + taskType
                + ", model=" + selectedModel
                + ", provider=" + selectedProvider
                + ", reason=" + reason + "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
