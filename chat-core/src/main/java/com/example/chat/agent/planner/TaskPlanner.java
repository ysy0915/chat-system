package com.example.chat.agent.planner;

import com.example.chat.agent.protocol.SubAgentPlan;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 任务规划器（TaskPlanner）—— Step 2 Orchestrator 指挥官模式。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>{@link #shouldDecompose}：识别超长/跨域/多任务请求（规则判定 + 可选 LLM 二次判定）；</li>
 *   <li>{@link #buildPlan}：调用 LLM 生成"任务拆解计划（Plan）"，输出 {@link SubAgentPlan}，</li>
 * </ol>
 * <p>主 Agent 不再亲自执行超长任务，而是输出计划并交由并行 Worker 执行。</p>
 */
@Service
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanner.class);

    /** 多任务连词/跨域信号：命中即倾向拆解 */
    private static final Pattern MULTI_TASK_PATTERN = Pattern.compile(
            "(同时|分别|一方面|另一方面|既要|又要|先.*然后.*最后|从.+角度|包括.{1,10}(和|、|以及).{1,10}(和|、)|."
            + "{0,6}(分析|撰写|调研|对比|设计|实现|排查)(.{0,8}(分析|撰写|调研|对比|设计|实现|排查)){1,})");

    /** 需要拆解的典型跨域任务关键词 */
    private static final Pattern CROSS_DOMAIN_PATTERN = Pattern.compile(
            "(报告|方案|策划|规划|调研|对比|竞品|市场分析|项目计划|实施计划|技术方案|架构设计|项目启动)");

    private final LLMInvoker llmInvoker;
    private final LlmConfigProperties llmConfig;
    private final ObjectMapper objectMapper;

    @Value("${app.agent.planner.min-length:120}")
    private int minLength;

    @Value("${app.agent.planner.max-tasks:5}")
    private int maxTasks;

    @Value("${app.agent.planner.llm-judge:false}")
    private boolean llmJudge;

    @Autowired
    public TaskPlanner(LLMInvoker llmInvoker, LlmConfigProperties llmConfig, ObjectMapper objectMapper) {
        this.llmInvoker = llmInvoker;
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断是否值得拆解为并行子任务。
     * 规则：长度超阈值 或 命中多任务连词 或 命中跨域任务关键词。
     */
    public boolean shouldDecompose(String question) {
        if (question == null || question.isBlank()) return false;
        String q = question.trim();
        if (q.length() >= minLength) {
            log.debug("[TaskPlanner] 长度 {} >= {} 触发拆解判定", q.length(), minLength);
            return true;
        }
        if (MULTI_TASK_PATTERN.matcher(q).find()) {
            log.debug("[TaskPlanner] 命中多任务连词，触发拆解");
            return true;
        }
        if (CROSS_DOMAIN_PATTERN.matcher(q).find()) {
            log.debug("[TaskPlanner] 命中跨域任务关键词，触发拆解");
            return true;
        }
        // 可选 LLM 二次判定（成本高，默认关闭）
        if (llmJudge) {
            return judgeByLlm(q);
        }
        return false;
    }

    /**
     * 生成任务拆解计划。失败返回 null（调用方降级原流程）。
     */
    public SubAgentPlan buildPlan(String question, ModelConfig config,
                                  String defaultBaseUrl, String defaultApiKey) {
        try {
            String systemPrompt = buildPlanSystemPrompt(question);
            List<LLMMessage> messages = List.of(new LLMMessage("system", systemPrompt));

            // 显式设置足够大的 max_tokens：部分中转网关默认输出上限较低，会截断计划 JSON
            String raw = llmInvoker.invoke(config, messages, 0.3, "planner",
                    defaultBaseUrl, defaultApiKey, 4096);
            if (raw == null || raw.isBlank()) {
                log.warn("[TaskPlanner] 计划生成返回空");
                return null;
            }
            SubAgentPlan plan = parsePlan(raw, question, config);
            if (plan == null || plan.tasks.isEmpty()) {
                log.warn("[TaskPlanner] 计划解析为空，原文: {}", truncate(raw, 200));
                return null;
            }
            log.info("[TaskPlanner] 计划生成成功 planId={} tasks={}", plan.planId, plan.tasks.size());
            return plan;
        } catch (Exception e) {
            log.warn("[TaskPlanner] 计划生成失败: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════════

    private String buildPlanSystemPrompt(String question) {
        int max = Math.min(9, maxTasks);
        return "你是任务规划引擎（TaskPlanner）。用户请求可能包含多个独立子任务或跨领域需求，"
                + "请将其拆解为可并行执行的子任务计划。\n"
                + "用户请求：\n" + question + "\n\n"
                + "拆解规则：\n"
                + "1. 输出 2~" + max + " 个彼此尽量独立的子任务（能同时执行，不互相等待），"
                + "请求包含几块相对独立的内容就拆几个（最多 " + max + " 个），拆得越细并行度越高、"
                + "每个子任务越小执行越快，切勿把所有内容塞进一个大任务；\n"
                + "2. 每个子任务必须自包含：执行子代理只看到 instructions 和 context_summary，"
                + "看不到完整对话，因此 instructions 必须包含完成该任务所需的全部背景与要求；\n"
                + "3. instructions 必须精炼，每条 ≤ 80 字；context_summary 从用户请求中提取与本子任务"
                + "直接相关的关键信息，≤ 100 字；\n"
                + "4. tools_scope 填写该子任务可能需要使用的工具名（如 knowledge_search、calculator、"
                + "weather、time），不确定就留空数组；\n"
                + "5. expected_output 一句话说明期望的结构化输出（如：JSON、分点结论、表格、代码）；\n"
                + "6. 仅输出严格紧凑的 JSON：不要 markdown 代码块、不要多余空格、不要换行、不要任何解释文字，"
                + "所有字段值内的引号用 \\\" 转义。整个输出控制在 2000 tokens 内。格式如下：\n"
                + "{\n"
                + "  \"title\": \"计划标题（一句话概括用户意图）\",\n"
                + "  \"tasks\": [\n"
                + "    {\n"
                + "      \"title\": \"子任务标题\",\n"
                + "      \"instructions\": \"给子代理的完整指令\",\n"
                + "      \"tools_scope\": [],\n"
                + "      \"context_summary\": \"与本子任务直接相关的关键信息\",\n"
                + "      \"expected_output\": \"期望输出格式描述\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";
    }

    /** 解析 LLM 输出为 SubAgentPlan；分配 planId / taskId */
    @SuppressWarnings("unchecked")
    private SubAgentPlan parsePlan(String raw, String question, ModelConfig config) throws Exception {
        String json = extractPlanJson(raw);

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception firstEx) {
            // 容错：LLM 输出在字符串中间被截断（如 max_tokens 截断）时补引号重试
            String repaired = repairTruncatedJson(json);
            if (repaired == null) {
                log.warn("[TaskPlanner] 计划 JSON 解析失败，原文={}", truncate(raw, 500));
                return null;
            }
            try {
                root = objectMapper.readTree(repaired);
            } catch (Exception ex) {
                log.warn("[TaskPlanner] 计划 JSON 修复后仍失败，原文={}", truncate(raw, 500));
                return null;
            }
        }

        SubAgentPlan plan = new SubAgentPlan();
        plan.planId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        plan.parentAgentId = "agent-main-" + config.provider;
        plan.originalQuestion = question;
        if (root.has("title") && !root.get("title").isNull()) {
            plan.title = root.get("title").asText();
        } else {
            plan.title = truncate(question, 40);
        }

        JsonNode tasksNode = root.get("tasks");
        if (tasksNode == null || !tasksNode.isArray()) return null;

        int index = 0;
        List<SubAgentTask> tasks = new ArrayList<>();
        for (JsonNode tn : tasksNode) {
            SubAgentTask task = new SubAgentTask();
            task.planId = plan.planId;
            task.parentAgentId = plan.parentAgentId;
            task.taskId = plan.planId + "-t" + (++index);
            task.title = tn.has("title") && !tn.get("title").isNull() ? tn.get("title").asText() : ("子任务" + index);
            task.instructions = tn.has("instructions") && !tn.get("instructions").isNull()
                    ? tn.get("instructions").asText() : "";
            task.contextSummary = tn.has("context_summary") && !tn.get("context_summary").isNull()
                    ? tn.get("context_summary").asText() : "";
            task.expectedOutput = tn.has("expected_output") && !tn.get("expected_output").isNull()
                    ? tn.get("expected_output").asText() : "";
            if (tn.has("tools_scope") && tn.get("tools_scope").isArray()) {
                List<String> scope = new ArrayList<>();
                tn.get("tools_scope").forEach(n -> scope.add(n.asText()));
                task.toolsScope = scope;
            }
            if (task.instructions.isBlank()) {
                log.warn("[TaskPlanner] 子任务 {} 无 instructions，跳过", task.taskId);
                continue;
            }
            tasks.add(task);
            if (tasks.size() >= maxTasks) break;
        }
        if (tasks.isEmpty()) return null;
        plan.tasks = tasks;
        return plan;
    }

    /** LLM 二次判定：是否需要拆解（返回布尔） */
    private boolean judgeByLlm(String question) {
        try {
            String systemPrompt = "判断下面用户请求是否属于 超长/跨域/多子任务 类任务，"
                    + "是输出 true，否输出 false，只输出一个单词：\n" + question;
            String raw = llmInvoker.invoke(defaultConfig(), List.of(new LLMMessage("user", systemPrompt)),
                    0.1, "planner", llmConfig.getBaseUrl(), llmConfig.getApiKey());
            return raw != null && raw.trim().toLowerCase().contains("true");
        } catch (Exception e) {
            log.debug("[TaskPlanner] LLM 判定失败，按不需要拆解处理: {}", e.getMessage());
            return false;
        }
    }

    private ModelConfig defaultConfig() {
        ModelConfig c = new ModelConfig();
        c.provider = llmConfig.getProvider();
        c.model = llmConfig.getModel();
        c.apiKeyEncrypted = llmConfig.getApiKey();
        return c;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 解包 LLM 原始输出为可解析的计划 JSON，兼容多种上游返回形态：
     * <ul>
     *   <li>直接输出计划 JSON（{@code {"title":...,"tasks":[...]}}）→ 原样返回；</li>
     *   <li>输出 chat.completion 响应体（{@code {"choices":[{"message":{"content":"计划JSON"}}]}}）→ 递归提取 content；</li>
     *   <li>第三方中转网关双嵌套（content 里又是完整响应体）→ 最多解包 3 层。</li>
     * </ul>
     */
    private String extractPlanJson(String raw) {
        String current = raw;
        for (int depth = 0; depth < 3; depth++) {
            int start = current.indexOf('{');
            int end = current.lastIndexOf('}');
            if (start < 0 || end <= start) return current;
            String candidate = current.substring(start, end + 1);
            JsonNode node;
            try {
                node = objectMapper.readTree(candidate);
            } catch (Exception e) {
                // 截断等无法解析，交给 repairTruncatedJson 兜底
                return candidate;
            }
            // chat.completion 响应体：提取 choices[0].message.content
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content") && !message.get("content").isNull()) {
                    String content = message.get("content").asText();
                    if (!content.isBlank()) {
                        current = content;
                        continue; // 可能再嵌套（中转网关双包一层）
                    }
                }
                return candidate;
            }
            // 计划结构或其他合法 JSON：直接返回
            return candidate;
        }
        return current;
    }

    /**
     * 粗修复 LLM 截断的 JSON：依次尝试补引号、补闭合括号组合，直到可解析。
     */
    private String repairTruncatedJson(String json) {
        // 1. 补引号（字符串值中间截断）
        String candidate = json;
        for (int i = 0; i < 3; i++) {
            candidate = candidate + "\"";
            try {
                objectMapper.readTree(candidate);
                log.debug("[TaskPlanner] JSON 截断修复成功（补 {} 个引号）", i + 1);
                return candidate;
            } catch (Exception ignored) {
                // 继续尝试
            }
        }
        // 2. 补闭合括号组合（对象/数组尾部截断）
        String[] suffixes = {"\"}", "\"]}", "\"}]}", "\"}]}"};
        for (String suffix : suffixes) {
            try {
                objectMapper.readTree(json + suffix);
                log.debug("[TaskPlanner] JSON 截断修复成功（补 {}）", suffix);
                return json + suffix;
            } catch (Exception ignored) {
                // 继续尝试
            }
        }
        return null;
    }
}
