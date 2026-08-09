package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.exception.ChatServiceException;
import com.example.chat.rag.service.ConversationMemoryService;
import com.example.chat.repository.TreeHoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 树洞历史上下文构建器
 * 负责构建多轮对话历史、记忆增强、历史压缩
 */
@Service
public class TreeHoleHistoryBuilder {

    private static final Logger log = LoggerFactory.getLogger(TreeHoleHistoryBuilder.class);

    /** 情绪树洞专属系统 prompt */
    static final String SYSTEM_PROMPT =
            "你是一个温暖的情感树洞，专门倾听用户内心的情绪与感受。" +
            "你具备以下特点：" +
            "1. 以温暖、包容、不评判的态度倾听和回应；" +
            "2. 先认可用户的感受，让用户感到被理解和接纳；" +
            "3. 给予情感支持，而不是简单地提供建议或解决方案；" +
            "4. 语言温柔亲切，像一个知心朋友；" +
            "5. 适当地引导用户正向思考，但不强行灌输；" +
            "6. 如果用户有心理危机迹象，温和地建议寻求专业帮助。" +
            "每次回复都应该让用户感受到被关爱和理解。";

    private final TreeHoleRepository treeHoleRepository;
    private final ChatHistoryBuilder chatHistoryBuilder;

    @Autowired(required = false)
    private ConversationMemoryService memoryService;

    @Autowired(required = false)
    private HistorySummaryService historySummaryService;

    public TreeHoleHistoryBuilder(TreeHoleRepository treeHoleRepository,
                                   ChatHistoryBuilder chatHistoryBuilder) {
        this.treeHoleRepository = treeHoleRepository;
        this.chatHistoryBuilder = chatHistoryBuilder;
    }

    /** 树洞历史上下文打包对象 */
    public record HistoryContext(List<LLMMessage> messages, String systemPrompt) {}

    /**
     * 构建树洞历史上下文：取最近10条 + 记忆增强 + 历史压缩
     */
    public HistoryContext build(long userId, String question) {
        List<TreeHoleMessage> recent = treeHoleRepository.findRecentByUserId(userId);
        int start = Math.max(0, recent.size() - 10);
        List<LLMMessage> historyMsgs = new ArrayList<>();
        for (int i = start; i < recent.size(); i++) {
            TreeHoleMessage prev = recent.get(i);
            historyMsgs.add(LLMMessage.user(prev.question));
            if (prev.answerJson != null && !prev.answerJson.isBlank()) {
                historyMsgs.add(LLMMessage.assistant(chatHistoryBuilder.extractAnswerText(prev.answerJson)));
            }
        }

        StringBuilder systemPrompt = new StringBuilder(SYSTEM_PROMPT);
        if (memoryService != null) {
            String memory = memoryService.buildMemoryContext("treehole", userId, question);
            if (memory != null && !memory.isBlank()) {
                systemPrompt.append("\n\n").append(memory);
            }
        }
        // 历史过长时压缩早期消息为摘要
        historyMsgs = compress(userId, historyMsgs, systemPrompt);

        return new HistoryContext(historyMsgs, systemPrompt.toString());
    }

    /** 树洞历史压缩 */
    public List<LLMMessage> compress(Long userId, List<LLMMessage> historyMsgs,
                                      StringBuilder systemPrompt) {
        if (historySummaryService != null && !historyMsgs.isEmpty()) {
            try {
                return historySummaryService.compress("treehole", userId, historyMsgs, systemPrompt);
            } catch (Exception e) {
                log.warn("treehole 历史压缩失败, fallback: {}", e.getMessage());
            }
        }
        return historyMsgs;
    }

    /**
     * 保存对话记忆（包装 try-catch，失败不阻塞）
     */
    public void saveMemoryIfAvailable(Long userId, String question, String answerJson) {
        if (memoryService != null && answerJson != null) {
            try {
                memoryService.saveConversation("treehole", userId, question, answerJson);
            } catch (Exception e) {
                log.warn("[Memory] 树洞记忆保存失败 user={} error={}", userId, e.getMessage());
            }
        }
    }
}
