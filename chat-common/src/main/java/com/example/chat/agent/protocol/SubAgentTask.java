package com.example.chat.agent.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理任务协议（Sub-Agent Protocol）—— Step 1。
 *
 * <p>标准 JSON 通信协议中的"任务指令"载体。主 Agent（Orchestrator）将超长/跨域任务
 * 拆解为多个 {@link SubAgentTask}，经 RabbitMQ 分发给独立 Worker 并行执行。</p>
 *
 * <p>每个子任务自包含（携带 instructions + context_summary），Worker 无需访问完整对话历史，
 * 拥有独立的极短上下文，天然规避单 Agent 上下文窗口限制。</p>
 *
 * <pre>{@code
 * {
 *   "task_id": "p1a3f2-t2",
 *   "plan_id": "p1a3f2",
 *   "parent_agent_id": "agent-main-9090",
 *   "instructions": "独立撰写市场分析章节，要求…",
 *   "tools_scope": ["knowledge_search", "calculator"],
 *   "context_summary": "目标产品：智能客服机器人；预算：50万",
 *   "expected_output": "结构化 JSON：{title, points[], conclusion}",
 *   "depends_on": []
 * }
 * }</pre>
 */
public class SubAgentTask {

    /** 子任务 ID（全局唯一：planId + 序号） */
    public String taskId;

    /** 所属计划 ID */
    public String planId;

    /** 父 Agent ID（发出该任务的主 Agent 标识） */
    public String parentAgentId;

    /** 任务标题（供前端展示） */
    public String title;

    /** 任务指令（自包含，Worker 只见此指令执行） */
    public String instructions;

    /** 允许使用的工具范围（ToolRegistry 中的工具名列表，空 = 不使用工具） */
    public List<String> toolsScope = new ArrayList<>();

    /** 极短上下文（从原请求提取的与该子任务相关的关键信息，≤200 字） */
    public String contextSummary;

    /** 期望输出格式描述（引导 Worker 产出结构化摘要） */
    public String expectedOutput;

    /** 依赖的子任务 ID（当前版本为空 = 全部并行；非空 = 分层串行） */
    public List<String> dependsOn = new ArrayList<>();

    /** 优先级（越大越优先，默认 5） */
    public int priority = 5;

    /** 超时毫秒数（Worker 侧软超时提示） */
    public long timeoutMs = 120_000;

    /** 创建时间戳 */
    public long createdAt = System.currentTimeMillis();

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String parentAgentId) { this.parentAgentId = parentAgentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public List<String> getToolsScope() { return toolsScope; }
    public void setToolsScope(List<String> toolsScope) { this.toolsScope = toolsScope != null ? toolsScope : new ArrayList<>(); }

    public String getContextSummary() { return contextSummary; }
    public void setContextSummary(String contextSummary) { this.contextSummary = contextSummary; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>(); }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
