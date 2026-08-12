package com.example.chat.agent.protocol;

/**
 * 子代理执行结果协议（Sub-Agent Protocol）—— Step 1。
 *
 * <p>Worker 执行完 {@link SubAgentTask} 后回传的结构化摘要载体。
 * 只回传 summary（结构化摘要），不携带完整上下文，由主 Agent 收敛为最终回答。</p>
 *
 * <pre>{@code
 * {
 *   "task_id": "p1a3f2-t2",
 *   "plan_id": "p1a3f2",
 *   "parent_agent_id": "agent-main-9090",
 *   "success": true,
 *   "summary": "市场分析结论：…（结构化要点）",
 *   "model": "qwen-plus",
 *   "duration_ms": 8231
 * }
 * }</pre>
 */
public class SubAgentResult {

    /** 子任务 ID（对应 SubAgentTask.taskId） */
    public String taskId;

    /** 所属计划 ID */
    public String planId;

    /** 父 Agent ID */
    public String parentAgentId;

    /** 是否执行成功 */
    public boolean success;

    /** 结构化摘要（执行结论/要点，供主 Agent 收敛） */
    public String summary;

    /** 失败原因（success=false 时填充） */
    public String error;

    /** 执行所用模型 */
    public String model;

    /** 执行耗时毫秒 */
    public long durationMs;

    /** 完成时间戳 */
    public long finishedAt = System.currentTimeMillis();

    public static SubAgentResult success(SubAgentTask task, String summary, String model, long durationMs) {
        SubAgentResult r = new SubAgentResult();
        r.taskId = task.taskId;
        r.planId = task.planId;
        r.parentAgentId = task.parentAgentId;
        r.success = true;
        r.summary = summary;
        r.model = model;
        r.durationMs = durationMs;
        return r;
    }

    public static SubAgentResult failure(SubAgentTask task, String error, long durationMs) {
        SubAgentResult r = new SubAgentResult();
        r.taskId = task.taskId;
        r.planId = task.planId;
        r.parentAgentId = task.parentAgentId;
        r.success = false;
        r.error = error;
        r.durationMs = durationMs;
        return r;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String parentAgentId) { this.parentAgentId = parentAgentId; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
}
