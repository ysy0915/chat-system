package com.example.chat.controller;

import com.example.chat.entity.Message;
import com.example.chat.entity.User;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.UserRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final MessageRepository messageRepository;
    private final RabbitTemplate rabbitTemplate;
    private final com.example.chat.service.ChatProcessor chatProcessor;
    private final UserRepository userRepository;
    private final boolean useRabbit;

    public MessageController(MessageRepository messageRepository, RabbitTemplate rabbitTemplate, com.example.chat.service.ChatProcessor chatProcessor, UserRepository userRepository, @org.springframework.beans.factory.annotation.Value("${app.use-rabbit:true}") boolean useRabbit) {
        this.messageRepository = messageRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.chatProcessor = chatProcessor;
        this.userRepository = userRepository;
        this.useRabbit = useRabbit;
    }

    @PostMapping
    public ResponseEntity<?> createMessage(@RequestBody Map<String, Object> body) {
        Object reqIdObj = body.get("req_id");
        String reqId = (reqIdObj == null || "null".equals(String.valueOf(reqIdObj)) || String.valueOf(reqIdObj).isBlank())
                ? UUID.randomUUID().toString()
                : String.valueOf(reqIdObj);

        Object questionObj = body.get("question");
        String question = questionObj == null ? "" : questionObj.toString();

        Long userId = body.get("user_id") == null ? 0L : Long.parseLong(body.get("user_id").toString());

        if (userRepository.findById(userId) == null) {
            User newUser = new User();
            newUser.email = "user_" + userId + "@chat.local";
            newUser.name = "用户" + userId;
            newUser.role = "user";
            newUser.passwordHash = "";
            userRepository.insert(newUser);
            userId = newUser.id;
            System.out.println("[DEBUG] Auto-created user id=" + userId);
        }

        // debug log to ensure reqId is generated
        System.out.println("[DEBUG] createMessage body=" + body + " resolved reqId=" + reqId);
        Message m = new Message();
        m.reqId = reqId;
        m.userId = userId;
        m.question = question;
        m.status = "queued";
        messageRepository.insert(m);

        // prepare payload
        Object preferred = body.get("preferred_model_config_id");
        Map<String, Object> messagePayload = new HashMap<>();
        messagePayload.put("req_id", reqId);
        messagePayload.put("user_id", userId);
        messagePayload.put("question", question);
        if (preferred != null) {
            messagePayload.put("preferred_model_config_id", preferred);
        }

        if (useRabbit) {
            try {
                rabbitTemplate.convertAndSend("chat.exchange", "chat.request", messagePayload);
            } catch (Exception ex) {
                // RabbitMQ not available — fallback to direct processing
                System.err.println("Rabbit send failed, falling back: " + ex.getMessage());
                chatProcessor.process(messagePayload);
            }
        } else {
            // directly process synchronously
            chatProcessor.process(messagePayload);
        }

        return ResponseEntity.accepted().body(Map.of("id", m.id, "req_id", reqId, "status", "queued", "user_id", userId));    }

    @GetMapping
    public ResponseEntity<?> listMessages(@RequestParam(value = "user_id", defaultValue = "0") Long userId) {
        List<Message> list = messageRepository.findByUserId(userId);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/all")
    public ResponseEntity<?> listAllMessages() {
        List<Message> list = messageRepository.findAllMessages();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/questions")
    public ResponseEntity<?> listQuestionsOnly() {
        List<Message> list = messageRepository.findQuestionsOnly();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchQuestions(@RequestParam("q") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        List<Message> list = messageRepository.searchQuestions(keyword.trim());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}/answer")
    public ResponseEntity<?> getAnswerById(@PathVariable Long id) {
        Message m = messageRepository.findAnswerById(id);
        if (m == null || m.answerJson == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("answer", m.answerJson));
    }
}
