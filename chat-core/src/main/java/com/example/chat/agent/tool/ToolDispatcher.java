package com.example.chat.agent.tool;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.ApiKeyResolver;
import com.example.chat.util.BaseUrlResolver;
import com.example.chat.util.LlmToolInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调度器
 *
 * 协调 LLM 与工具的调用流程：
 *   1. 把工具列表（function schema）加入请求的 tools 参数
 *   2. 调用 LLM（千问支持 function calling）
 *   3. 如果 LLM 返回 tool_calls，执行对应工具
 *   4. 把工具结果加入 messages 再调 LLM 生成最终回答
 *   5. 如果没有 tool_calls，直接返回 LLM 回答
 *
 * 受 app.agent.enabled=true 控制
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final ToolRegistry toolRegistry;
    private final LLMInvoker llmInvoker;
    private final BaseUrlResolver baseUrlResolver;
    private final LlmToolInvoker llmToolInvoker;

    /** 技能自进化服务（Step 3，同 app.agent.enabled 开关） */
    @Autowired(required = false)
    private com.example.chat.agent.skill.SkillEvolutionService skillEvolutionService;

    @Value("${app.agent.max-tool-calls:3}")
    private int maxToolCalls;

    @Autowired
    public ToolDispatcher(ToolRegistry toolRegistry,
                          LLMInvoker llmInvoker,
                          BaseUrlResolver baseUrlResolver,
                          LlmToolInvoker llmToolInvoker) {
        this.toolRegistry = toolRegistry;
        this.llmInvoker = llmInvoker;
        this.baseUrlResolver = baseUrlResolver;
        this.llmToolInvoker = llmToolInvoker;
    }

    /**
     * 工具调度主入口（非流式）
     *
     * @param userInput   用户原始输入（用于日志，可为 null）
     * @param config      模型配置
     * @param messages    当前对话消息列表（会被本方法修改/扩展）
     * @param temperature 温度
     * @param scene       调用场景
     * @param defaultBaseUrl 默认 baseUrl
     * @param defaultApiKey  默认 apiKey
     * @return 最终回答（如果触发了工具，则是工具增强后的回答）
     */
    @SuppressWarnings({"unchecked", "PMD.AvoidBranchingStatementAsLastInLoop"})
    public String dispatch(String userInput, ModelConfig config,
                           List<LLMMessage> messages,
                           double temperature, String scene,
                           String defaultBaseUrl, String defaultApiKey) throws Exception {
        // 没有注册工具：返回 null，让调用方走正常流式路径
        if (!toolRegistry.hasTools()) {
            log.debug("[ToolDispatcher] 无可用工具，走流式路径");
            return null;
        }

        List<Map<String, Object>> toolsSchema = toolRegistry.getToolsSchema();

        // 使用消息的副本进行多轮工具调用 (转为 Map 给底层 API)
        List<Map<String, Object>> workingMessages = new ArrayList<>(LLMMessage.toMapList(messages));

        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = ApiKeyResolver.resolve(config, defaultApiKey);

        int callCount = 0;
        // Step3 技能自进化：记录本任务链实际执行的工具名
        List<String> executedTools = new ArrayList<>();
        while (callCount < maxToolCalls) {
            // 带工具调用 LLM
            Map<String, Object> llmResp = llmToolInvoker.callWithTools(config, baseUrl, apiKey,
                    LlmToolInvoker.fromMapList(workingMessages), temperature, toolsSchema);

            List<Map<String, Object>> toolCalls = llmToolInvoker.extractToolCalls(llmResp);
            String assistantContent = llmToolInvoker.extractContent(llmResp);

            if (toolCalls == null || toolCalls.isEmpty()) {
                // LLM 未请求工具：返回 null，让调用方走正常流式路径（含思考链）
                log.info("[ToolDispatcher] LLM 未触发工具调用，走流式路径 (callCount={}, scene={})", callCount, scene);
                return null;
            }

            // 把 assistant 这条带 tool_calls 的消息加入 messages
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            if (assistantContent != null && !assistantContent.isEmpty()) {
                assistantMsg.put("content", assistantContent);
            } else {
                assistantMsg.put("content", "");
            }
            assistantMsg.put("tool_calls", toolCalls);
            workingMessages.add(assistantMsg);

            // 执行每一个工具调用，把结果作为 tool 角色消息回填
            for (Map<String, Object> tc : toolCalls) {
                String toolResult = llmToolInvoker.executeOneToolCall(tc, this::executeTool);
                String toolCallId = llmToolInvoker.toolNameOf(tc);
                if (!executedTools.contains(toolCallId)) {
                    executedTools.add(toolCallId);
                }

                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("name", toolCallId);
                toolMsg.put("content", toolResult);
                workingMessages.add(toolMsg);
            }

            callCount++;
            log.info("[ToolDispatcher] 工具调用第 {} 轮完成，执行了 {} 个工具 (scene={})",
                    callCount, toolCalls.size(), scene);

            // 调用 LLM 生成最终回答（仍带 tools，允许 LLM 继续调用工具直到 maxToolCalls）
            String finalAnswer = llmInvoker.invoke(config, LlmToolInvoker.fromMapList(workingMessages), temperature, scene,
                    defaultBaseUrl, defaultApiKey);
            // Step3：任务链成功执行工具后，异步触发技能复盘沉淀
            triggerEvolution(userInput, executedTools, finalAnswer, scene, config,
                    defaultBaseUrl, defaultApiKey);
            return finalAnswer;
        }

        log.warn("[ToolDispatcher] 达到最大工具调用次数 {}，停止 (scene={})", maxToolCalls, scene);
        // 超过上限：用最后一条消息直接调 LLM（不带工具，强制输出文本）
        String lastAnswer = llmInvoker.invoke(config, LlmToolInvoker.fromMapList(workingMessages), temperature, scene,
                defaultBaseUrl, defaultApiKey);
        triggerEvolution(userInput, executedTools, lastAnswer, scene, config, defaultBaseUrl, defaultApiKey);
        return lastAnswer;
    }

    /**
     * Step3 技能自进化：成功执行过工具的任务链，异步复盘沉淀技能。
     */
    private void triggerEvolution(String userInput, List<String> executedTools, String finalAnswer,
                                  String scene, ModelConfig config,
                                  String defaultBaseUrl, String defaultApiKey) {
        if (skillEvolutionService == null || executedTools == null || executedTools.isEmpty()) return;
        try {
            skillEvolutionService.evolveAsync(config, userInput,
                    String.join("; ", executedTools), finalAnswer, scene,
                    defaultBaseUrl, defaultApiKey);
        } catch (Exception e) {
            log.debug("[ToolDispatcher] 技能复盘触发失败: {}", e.getMessage());
        }
    }

    /** 从工具注册中心按名执行工具；未知工具返回占位提示 */
    private String executeTool(String toolName, Map<String, Object> params) {
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("[ToolDispatcher] 未知工具: {}", toolName);
            return "[未知工具: " + toolName + "]";
        }
        return tool.execute(params);
    }
}
