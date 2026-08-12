package com.example.chat.agent.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务拆解计划（Plan）—— Step 2 Orchestrator 产物。
 *
 * <p>主 Agent 识别到超长/跨域任务后，由 TaskPlanner 调用 LLM 生成该计划，
 * 内含若干可并行执行的 {@link SubAgentTask}，随后经 RabbitMQ 分发。</p>
 */
public class SubAgentPlan {

    /** 计划 ID（全局唯一，作为收敛聚合的 correlation key） */
    public String planId;

    /** 父 Agent ID */
    public String parentAgentId;

    /** 原始用户问题 */
    public String originalQuestion;

    /** 计划标题 */
    public String title;

    /** 子任务列表 */
    public List<SubAgentTask> tasks = new ArrayList<>();

    /** 创建时间戳 */
    public long createdAt = System.currentTimeMillis();

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String parentAgentId) { this.parentAgentId = parentAgentId; }

    public String getOriginalQuestion() { return originalQuestion; }
    public void setOriginalQuestion(String originalQuestion) { this.originalQuestion = originalQuestion; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<SubAgentTask> getTasks() { return tasks; }
    public void setTasks(List<SubAgentTask> tasks) { this.tasks = tasks != null ? tasks : new ArrayList<>(); }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
