package com.example.chat.controller;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.example.chat.service.DebateProcessor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/debate")
public class DebateController {
    private final DebateProcessor debateProcessor;
    private final MessageRepository messageRepository;

    public DebateController(DebateProcessor debateProcessor, MessageRepository messageRepository) {
        this.debateProcessor = debateProcessor;
        this.messageRepository = messageRepository;
    }

    @PostMapping
    public ResponseEntity<?> startDebate(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") != null ? body.get("req_id").toString() : UUID.randomUUID().toString();
        String question = body.get("question") != null ? body.get("question").toString() : "";
        Long userId = body.get("user_id") == null ? 0L : Long.parseLong(body.get("user_id").toString());

        Message m = new Message();
        m.reqId = reqId;
        m.userId = userId;
        m.question = question;
        m.status = "debating";
        m.isPrivate = 0;
        messageRepository.insert(m);

        Map<String, Object> payload = new HashMap<>();
        payload.put("req_id", reqId);
        payload.put("question", question);
        payload.put("user_id", userId);
        debateProcessor.process(payload);

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "debating"));
    }
}
