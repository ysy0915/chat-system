package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 技能（Step 3 技能自进化）—— 对应 MySQL skill_registry 表。
 *
 * <p>Agent 成功执行复杂 ReAct 任务链后，由 LLM 复盘生成的标准函数代码，
 * 下次遇到类似请求时可直接注入 System Prompt 复用。</p>
 */
@Schema(description = "技能注册中心")
public class Skill {

    @Schema(description = "主键")
    public Long id;

    @Schema(description = "技能名（唯一）")
    public String name;

    @Schema(description = "技能描述")
    public String description;

    @Schema(description = "代码语言 java / python")
    public String language = "java";

    @Schema(description = "生成的函数代码")
    public String code;

    @Schema(description = "触发/使用说明（注入 System Prompt 的指令）")
    public String triggerPrompt;

    @Schema(description = "来源追溯（触发对话 + 工具链摘要）")
    public String sourceTrace;

    @Schema(description = "被使用次数")
    public Integer usageCount = 0;

    @Schema(description = "状态 1启用 0禁用")
    public Integer status = 1;

    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();

    @Schema(description = "更新时间")
    public java.time.Instant updatedAt = java.time.Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getTriggerPrompt() { return triggerPrompt; }
    public void setTriggerPrompt(String triggerPrompt) { this.triggerPrompt = triggerPrompt; }

    public String getSourceTrace() { return sourceTrace; }
    public void setSourceTrace(String sourceTrace) { this.sourceTrace = sourceTrace; }

    public Integer getUsageCount() { return usageCount; }
    public void setUsageCount(Integer usageCount) { this.usageCount = usageCount; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }

    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }
}
