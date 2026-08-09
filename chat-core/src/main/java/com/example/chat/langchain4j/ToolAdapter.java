package com.example.chat.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.chat.agent.tool.Tool;
import com.example.chat.agent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 工具适配器：把项目现有的 Tool 接口适配为 LangChain4j 格式
 *
 * LangChain4j 用 @Tool 注解 + 反射来识别工具，但我们的工具是动态注册的接口实现。
 * 这个适配器把 ToolRegistry 中的工具转换为 ToolSpecification 列表，
 * 并提供执行方法。
 */
public class ToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(ToolAdapter.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolAdapter(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取所有工具的 LangChain4j 规格描述
     */
    public List<ToolSpecification> getToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Tool tool : toolRegistry.getAllTools()) {
            specs.add(ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .build());
        }
        return specs;
    }

    /**
     * 执行工具调用
     * @param request LangChain4j 的工具执行请求
     * @return 工具执行结果消息
     */
    public ToolExecutionResultMessage executeTool(ToolExecutionRequest request) {
        String toolName = request.name();
        String argumentsJson = request.arguments();

        log.info("[ToolAdapter] 执行工具: {} args={}", toolName, argumentsJson);

        try {
            Tool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                return ToolExecutionResultMessage.from(request, "工具不存在: " + toolName);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> params = argumentsJson != null && !argumentsJson.isBlank()
                    ? objectMapper.readValue(argumentsJson, Map.class)
                    : Collections.emptyMap();

            String result = tool.execute(params);
            log.info("[ToolAdapter] 工具 {} 执行完成 result={}", toolName,
                    result != null && result.length() > 100 ? result.substring(0, 100) + "..." : result);
            return ToolExecutionResultMessage.from(request, result);
        } catch (Exception e) {
            log.error("[ToolAdapter] 工具 {} 执行失败: {}", toolName, e.getMessage());
            return ToolExecutionResultMessage.from(request, "工具执行失败: " + e.getMessage());
        }
    }
}
