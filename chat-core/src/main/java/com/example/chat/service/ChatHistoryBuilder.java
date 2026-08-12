package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话历史构建器 — 负责将数据库历史消息+记忆上下文组装为 LLM 消息列表。
 */
@Service
public class ChatHistoryBuilder {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryBuilder.class);
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /** RAG 客户端（通过 /internal/rag/* 调用 chat-llm 的对话记忆） */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.chat.client.RagClient ragClient;

    /** 历史对话摘要服务（可选注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HistorySummaryService historySummaryService;

    public ChatHistoryBuilder(MessageRepository messageRepository, ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /** 个人对话历史消息 */
    public List<LLMMessage> buildPersonal(Long userId, String currentQuestion) {
        List<LLMMessage> historyMsgs = buildFromRecent(userId);

        StringBuilder systemPrompt = new StringBuilder("你是用户的AI助手，请友好地回答问题。");
        appendMemory("personal", userId, currentQuestion, systemPrompt);

        historyMsgs = compressHistory("personal", userId, historyMsgs, systemPrompt);

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(systemPrompt.toString()));
        messages.addAll(historyMsgs);
        messages.add(LLMMessage.user(currentQuestion));
        return messages;
    }

    /** 群聊历史消息 */
    public List<LLMMessage> buildGroup(Long userId, String currentQuestion) {
        List<LLMMessage> messages = new ArrayList<>();
        StringBuilder systemPrompt = new StringBuilder("你是AI伙伴群聊中的AI角色，请友好、有趣地回答问题。");
        appendMemory("chat", userId, currentQuestion, systemPrompt);
        messages.add(LLMMessage.system(systemPrompt.toString()));
        messages.add(LLMMessage.user(currentQuestion));
        return messages;
    }

    /** 文件对话历史消息 */
    public List<LLMMessage> buildFile(Long userId, String question, String fileName,
                                       String fileTextContent, boolean isImage,
                                       String fileBase64, String mimeType) {
        List<LLMMessage> historyMsgs = buildFromRecent(userId);

        StringBuilder systemPrompt = new StringBuilder("请根据提供的文件内容和对话历史，友好地回答用户问题。");
        historyMsgs = compressHistory("file", userId, historyMsgs, systemPrompt);

        List<LLMMessage> messages = new ArrayList<>();
        if (systemPrompt.indexOf("历史对话摘要") >= 0) {
            messages.add(LLMMessage.system(systemPrompt.toString()));
        }
        messages.addAll(historyMsgs);

        if (isImage) {
            String imageQuestion = (question == null || question.isBlank()) ? "请描述这张图片" : question;
            messages.add(LLMMessage.userWithImage(imageQuestion, fileBase64, mimeType));
        } else {
            String content = question + "\n\n--- 以下是文件 [" + fileName + "] 的内容 ---\n" + fileTextContent + "\n--- 文件内容结束 ---";
            messages.add(LLMMessage.user(content));
        }
        return messages;
    }

    // ---------- 内部方法 ----------

    private List<LLMMessage> buildFromRecent(Long userId) {
        List<LLMMessage> historyMsgs = new ArrayList<>();
        try {
            List<Message> recent = messageRepository.findRecentByUserId(userId);
            if (recent != null) {
                for (Message m : recent) {
                    historyMsgs.add(LLMMessage.user(m.question));
                    historyMsgs.add(LLMMessage.assistant(extractAnswerText(m.answerJson)));
                }
            }
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("[WARN] Failed to load recent messages for user {}: {}", userId, ex.getMessage());
        }
        return historyMsgs;
    }

    private void appendMemory(String scene, Long userId, String question, StringBuilder prompt) {
        try {
            String memory = ragClient.memoryContext(scene, userId, question);
            if (memory != null && !memory.isBlank()) {
                prompt.append("\n\n").append(memory);
            }
        } catch (Exception ex) {
            log.warn("[Memory] buildMemoryContext failed scene={} user={}: {}", scene, userId, ex.getMessage());
        }
    }

    private List<LLMMessage> compressHistory(String scene, Long userId,
                                              List<LLMMessage> historyMsgs,
                                              StringBuilder systemPrompt) {
        if (historySummaryService != null && !historyMsgs.isEmpty()) {
            try {
                return historySummaryService.compress(scene, userId, historyMsgs, systemPrompt);
            } catch (Exception e) {
                log.warn("[HistorySummary] compress failed, fallback to full history: {}", e.getMessage());
            }
        }
        return historyMsgs;
    }

    /** 从 answerJson 中提取纯文本回答 */
    public String extractAnswerText(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) return "";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.readValue(answerJson, Map.class);
            Object answer = m.get("answer");
            return answer != null ? answer.toString() : answerJson;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return answerJson;
        }
    }
}
