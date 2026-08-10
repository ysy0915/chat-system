package com.example.chat.service;

import com.example.chat.entity.DebateRecord;
import com.example.chat.entity.Message;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 批量导入服务 —— 将历史消息和辩论记录批量导入知识图谱。
 */
@Service
public class BatchImportService {

    private static final Logger log = LoggerFactory.getLogger(BatchImportService.class);

    private static final int BATCH_SIZE = 20;

    private final MessageRepository messageRepository;
    private final DebateRecordRepository debateRecordRepository;
    private final TripleExtractionService tripleExtractionService;
    private final GraphRepositoryService graphRepositoryService;
    private final ObjectMapper objectMapper;

    public BatchImportService(MessageRepository messageRepository,
                              DebateRecordRepository debateRecordRepository,
                              TripleExtractionService tripleExtractionService,
                              GraphRepositoryService graphRepositoryService,
                              ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.debateRecordRepository = debateRecordRepository;
        this.tripleExtractionService = tripleExtractionService;
        this.graphRepositoryService = graphRepositoryService;
        this.objectMapper = objectMapper;
    }

    /** 执行批量导入，返回总三元组数 */
    public int execute(Driver neo4jDriver) {
        int totalTriples = 0;

        // 导入消息
        int offset = 0;
        int msgTriples = 0;
        while (true) {
            List<Message> messages = messageRepository.findAllWithAnswers(offset, BATCH_SIZE);
            if (messages.isEmpty()) break;

            for (Message m : messages) {
                try {
                    String answer = parseAnswer(m.answerJson);
                    if (answer == null || answer.isBlank()) continue;
                    List<Map<String, String>> triples = tripleExtractionService.extractTriples(m.question, answer);
                    if (!triples.isEmpty()) {
                        String source = (m.isPrivate != null && m.isPrivate == 1) ? "personal" : "chat";
                        graphRepositoryService.saveTriples(neo4jDriver, triples, m.id, source, m.question);
                        msgTriples += triples.size();
                    }
                } catch (Exception e) {
                    log.warn("[KG-Batch] 导入消息 {} 失败: {}", m.id, e.getMessage());
                }
            }
            offset += BATCH_SIZE;
            log.info("[KG-Batch] 进度: offset={}, 三元组={}", offset, msgTriples);

            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        totalTriples += msgTriples;

        // 导入辩论记录
        try {
            List<DebateRecord> debates = debateRecordRepository.findAll();
            int debateTriples = 0;
            for (DebateRecord dr : debates) {
                try {
                    if (dr.finalAnswer == null || dr.finalAnswer.isBlank()) continue;
                    List<Map<String, String>> triples = tripleExtractionService.extractTriples(dr.question, dr.finalAnswer);
                    if (!triples.isEmpty()) {
                        graphRepositoryService.saveTriples(neo4jDriver, triples, (long) dr.id, "debate", dr.question);
                        debateTriples += triples.size();
                    }
                } catch (Exception e) {
                    log.warn("[KG-Batch] 导入辩论 {} 失败: {}", dr.id, e.getMessage());
                }
            }
            log.info("[KG-Batch] 辩论导入: {} 三元组", debateTriples);
            totalTriples += debateTriples;
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("[KG-Batch] 导入辩论记录失败: {}", e.getMessage());
        }

        log.info("[KG-Batch] 完成! 总三元组 {}", totalTriples);
        return totalTriples;
    }

    private String parseAnswer(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(answerJson, Map.class);
            Object answer = parsed.get("answer");
            return answer != null ? answer.toString() : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return answerJson; // 可能是纯文本
        }
    }
}
