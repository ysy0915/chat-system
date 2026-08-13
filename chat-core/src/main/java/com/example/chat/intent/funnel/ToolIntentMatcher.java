package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentRecognitionService;
import com.example.chat.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 意图漏斗 — 第三层：LLM / MCP 深度理解。
 *
 * <pre>
 *   当前版本对接已有的 IntentRecognitionService（LLM 分类）。
 *   MCP（Model Context Protocol）预留扩展点：
 *     - 识别到 TASK_EXECUTION 后可调用 MCP Server 的工具
 *     - 直接打通「意图 → 工具调用 → 执行结果」
 *
 *   命中率目标：< 10%（仅复杂意图走到这一层）
 * </pre>
 */
@Component
public class ToolIntentMatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolIntentMatcher.class);

    @Autowired(required = false)
    private IntentRecognitionService intentService;

    /**
     * LLM 深度意图分类。
     *
     * @param text  用户输入
     * @param scene 场景（personal / group）
     * @return 意图结果（失败返回 UNKNOWN）
     */
    public IntentResult classify(String text, String scene) {
        if (intentService == null) {
            log.debug("[ToolMatcher] IntentRecognitionService 不可用");
            return IntentResult.unknown();
        }
        try {
            IntentResult result = intentService.recognize(text, scene);
            log.info("[ToolMatcher] LLM 分类: intent={} confidence={:.2f}",
                     result.category(), result.confidence());
            return result;
        } catch (Exception e) {
            log.debug("[ToolMatcher] LLM 分类失败: {}", e.getMessage());
            return IntentResult.unknown();
        }
    }

    /**
     * MCP 工具执行（预留扩展点）。
     *
     * <pre>
     *   当意图为 TASK_EXECUTION 时，尝试查找匹配的 MCP Tool：
     *     1. 提取 entities 中的 action / target
     *     2. 在 MCP Server 中匹配 tool 定义
     *     3. 调用并返回结果
     * </pre>
     */
    public Optional<MCPToolResult> executeTool(IntentResult intent, String userId) {
        if (intent == null || intent.category() != IntentCategory.TASK_EXECUTION) {
            return Optional.empty();
        }
        // 扩展点（预留）：MCP 集成，引入 MCPClient 后启用
        // MCPClient client = mcpClientFactory.getClient("default");
        // List<Tool> tools = client.listTools();
        // Tool matched = matchTool(intent.entities(), tools);
        // return client.callTool(matched.name(), params);
        return Optional.empty();
    }

    /** MCP 工具执行结果 */
    public record MCPToolResult(String toolName, String result, long durationMs) {}
}
