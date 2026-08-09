package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 三元组抽取服务 —— 负责 LLM 调用 + JSON 解析，屏蔽 LLMInvoker / DirectLLMClient 两种调用路径。
 * 不涉及 Neo4j 操作。
 */
@Service
public class TripleExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TripleExtractionService.class);

    static final int QUESTION_TRUNCATE_LENGTH = 500;
    static final int ANSWER_TRUNCATE_LENGTH = 2000;

    private final ObjectMapper objectMapper;
    private final ModelConfigRepository modelConfigRepository;
    private final BaseUrlResolver baseUrlResolver;
    private final LlmConfigProperties llmConfig;
    private final DirectLLMClient directLLMClient;

    @Autowired(required = false)
    private LLMInvoker llmInvoker;

    public TripleExtractionService(ObjectMapper objectMapper,
                                   ModelConfigRepository modelConfigRepository,
                                   BaseUrlResolver baseUrlResolver,
                                   LlmConfigProperties llmConfig,
                                   DirectLLMClient directLLMClient) {
        this.objectMapper = objectMapper;
        this.modelConfigRepository = modelConfigRepository;
        this.baseUrlResolver = baseUrlResolver;
        this.llmConfig = llmConfig;
        this.directLLMClient = directLLMClient;
    }

    /** 调用 LLM 抽取知识三元组 */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> extractTriples(String question, String answer) {
        String q = question.length() > QUESTION_TRUNCATE_LENGTH ? question.substring(0, QUESTION_TRUNCATE_LENGTH) : question;
        String a = answer.length() > ANSWER_TRUNCATE_LENGTH ? answer.substring(0, ANSWER_TRUNCATE_LENGTH) : answer;

        String prompt = buildExtractionPrompt(q, a);
        String content = callLLM(prompt);
        if (content == null || content.isBlank()) return List.of();

        return parseTriplesResponse(content);
    }

    // ---- 私有方法 ----

    private String buildExtractionPrompt(String question, String answer) {
        return """
            你是一个知识抽取专家。从以下问答中抽取知识三元组（实体-关系-实体）。

            规则：
            1. 只抽取客观知识、概念、技术、因果关系，不抽取情绪、感受、个人隐私
            2. 不抽取人名、邮箱、手机号、地址等隐私信息
            3. 每条三元组包含 subject（主体）、relation（关系）、object（客体）
            4. 返回 JSON 格式，不要有多余内容

            示例：
            {"triples": [{"subject":"实体1","relation":"关系","object":"实体2"}]}

            问题：%s
            回答：%s
            """.formatted(question, answer);
    }

    private String callLLM(String prompt) {
        try {
            if (llmInvoker != null) {
                ModelConfig config = resolveModelConfig();
                if (config != null) {
                    String content = llmInvoker.invoke(config, prompt, 0.1, "knowledge-graph",
                            llmConfig.getBaseUrl(), llmConfig.getApiKey());
                    if (content != null && !content.isBlank()) return content;
                }
            }
            // 降级为直接 HTTP 调用
            return callLLMDirect(prompt);
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] LLM 抽取异常: {}", e.getMessage());
            return null;
        }
    }

    private ModelConfig resolveModelConfig() {
        try {
            List<ModelConfig> configs = modelConfigRepository.findAllEnabledByType("chat");
            if (configs == null || configs.isEmpty()) return null;
            return configs.stream()
                    .filter(c -> "qwen".equalsIgnoreCase(c.provider) || "deepseek".equalsIgnoreCase(c.provider))
                    .findFirst()
                    .orElse(configs.get(0));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 获取模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    private String callLLMDirect(String prompt) {
        String apiKey = llmConfig.getApiKey();
        String baseUrl = llmConfig.getBaseUrl();
        String model = llmConfig.getModel();

        try {
            List<ModelConfig> configs = modelConfigRepository.findAllEnabledByType("chat");
            if (configs != null && !configs.isEmpty()) {
                ModelConfig chosen = configs.stream()
                        .filter(c -> "qwen".equalsIgnoreCase(c.provider) || "dashscope".equalsIgnoreCase(c.provider))
                        .findFirst()
                        .orElse(configs.get(0));
                if (chosen.apiKeyEncrypted != null && !chosen.apiKeyEncrypted.isBlank()) {
                    apiKey = chosen.apiKeyEncrypted;
                }
                baseUrl = baseUrlResolver.resolve(chosen, baseUrl);
                if (chosen.model != null && !chosen.model.isBlank()) model = chosen.model;
            }
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 获取模型配置失败，使用默认配置: {}", e.getMessage());
        }

        try {
            return directLLMClient.call(baseUrl, apiKey, model,
                    List.of(LLMMessage.system("你是知识抽取助手，只返回JSON。"), LLMMessage.user(prompt)),
                    0.1, -1);
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] LLM 直接调用失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseTriplesResponse(String content) {
        content = content.trim();
        if (content.contains("```")) {
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start >= 0 && end > start) {
                content = content.substring(start, end + 1);
            }
        }

        try {
            Map<String, Object> result = objectMapper.readValue(content, Map.class);
            List<Map<String, Object>> triples = (List<Map<String, Object>>) result.get("triples");
            if (triples == null) return List.of();

            List<Map<String, String>> parsed = new ArrayList<>();
            for (Map<String, Object> t : triples) {
                String subject = (String) t.get("subject");
                String relation = (String) t.get("relation");
                String object = (String) t.get("object");
                if (subject != null && !subject.isBlank() && relation != null && !relation.isBlank()
                        && object != null && !object.isBlank()) {
                    parsed.add(Map.of(
                            "subject", subject.trim(),
                            "relation", relation.trim(),
                            "object", object.trim()
                    ));
                }
            }
            return parsed;
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 解析三元组JSON失败: {}", e.getMessage());
            return List.of();
        }
    }
}
