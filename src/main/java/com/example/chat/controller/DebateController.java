package com.example.chat.controller;
import com.example.chat.entity.DebateRecord;
import com.example.chat.entity.Message;
import com.example.chat.entity.User;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.UserRepository;
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
    private final DebateRecordRepository debateRecordRepository;
    private final UserRepository userRepository;

    public DebateController(DebateProcessor debateProcessor, MessageRepository messageRepository,
                            DebateRecordRepository debateRecordRepository, UserRepository userRepository) {
        this.debateProcessor = debateProcessor;
        this.messageRepository = messageRepository;
        this.debateRecordRepository = debateRecordRepository;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<?> startDebate(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") != null ? body.get("req_id").toString() : UUID.randomUUID().toString();
        String question = body.get("question") != null ? body.get("question").toString() : "";
        Long userId = body.get("user_id") == null ? 0L : Long.parseLong(body.get("user_id").toString());

        String userName = "";
        try {
            User user = userRepository.findById(userId);
            if (user != null && user.nickname != null && !user.nickname.isBlank()) {
                userName = user.nickname;
            }
        } catch (Exception ignored) {}
        if (userName.isBlank()) {
            userName = "用户" + userId;
        }

        DebateRecord record = new DebateRecord();
        record.userId = userId;
        record.userName = userName;
        record.question = question;
        record.status = "debating";
        debateRecordRepository.insert(record);

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
        payload.put("debate_record_id", record.id);
        payload.put("user_name", userName);
        debateProcessor.process(payload);

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "debating"));
    }
}
