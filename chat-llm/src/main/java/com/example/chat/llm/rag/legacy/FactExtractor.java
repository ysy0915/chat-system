package com.example.chat.llm.rag.legacy;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.service.LLMInvokeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h2>用户事实抽取器（L2 事实记忆共用）</h2>
 *
 * <p>调用 LLM 从一轮对话中抽取关于用户的持久事实（JSON 数组），
 * Milvus 版 {@link UserFactMemoryService} 与纯内存版
 * {@link InMemoryUserFactMemoryService} 共用，避免提示词重复。</p>
 */
@Component
public class FactExtractor {

    private static final Logger log = LoggerFactory.getLogger(FactExtractor.class);

    @Value("${app.rag.memory.fact-provider:qwen}")
    private String factProvider;

    @Value("${app.rag.memory.fact-model:qwen-turbo}")
    private String factModel;

    @Value("${app.rag.memory.fact-max-per-round:5}")
    private int maxFactsPerRound;

    private final LLMInvokeService llmInvokeService;
    private final ObjectMapper objectMapper;

    public FactExtractor(LLMInvokeService llmInvokeService, ObjectMapper objectMapper) {
        this.llmInvokeService = llmInvokeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 LLM 抽取关键事实（JSON 数组）。
     *
     * @return 事实列表（无新事实返回空列表）
     */
    @SuppressWarnings("unchecked")
    public List<String> extractFacts(String question, String answer) {
        try {
            String systemPrompt =
                    "你是一名用户记忆分析师，任务是从用户与AI的对话中抽取关于用户的【持久事实】。\n" +
                    "持久事实 = 用户的身份、职业、偏好、习惯、背景等，能在未来对话中帮助AI更好地服务该用户。\n" +
                    "规则：\n" +
                    "1. 只抽取关于用户的明确事实（如\"用户是Java开发者\"、\"用户不喜欢冗长的解释\"）；\n" +
                    "2. 忽略AI回答的内容本身、一次性问题（如时间天气查询）、无信息量的寒暄；\n" +
                    "3. 每条事实为简短的陈述句（≤30字），不得出现\"用户说\"\"用户表示\"等前缀；\n" +
                    "4. 最多抽取5条；没有可抽取的事实则输出空数组 [];\n" +
                    "5. 仅输出JSON数组字符串，不要输出任何解释或多余文字。";

            String userContent = "用户的发言：\n" + question +
                    "\n\nAI的回应：\n" + (answer != null ? answer : "");

            LangChainRequest req = new LangChainRequest();
            req.setBizType("RAG");
            req.setProvider(factProvider);
            req.setModel(factModel);
            req.setTemperature(0.1);
            req.setMaxTokens(500);
            req.setMessages(List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            ));

            LangChainResponse resp = llmInvokeService.invoke(req);
            if (!resp.isSuccess() || resp.getContent() == null || resp.getContent().isBlank()) {
                log.warn("[FactExtractor] 抽取失败 provider={} error={}", factProvider, resp.getError());
                return List.of();
            }
            String content = resp.getContent().trim();
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();

            List<Object> raw = objectMapper.readValue(content.substring(start, end + 1), List.class);
            List<String> facts = new ArrayList<>();
            for (Object o : raw) {
                String s = String.valueOf(o).trim();
                if (s.length() > 2 && s.length() <= 60 && !facts.contains(s)) {
                    facts.add(s);
                }
                if (facts.size() >= maxFactsPerRound) break;
            }
            return facts;
        } catch (Exception e) {
            log.warn("[FactExtractor] 抽取解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
