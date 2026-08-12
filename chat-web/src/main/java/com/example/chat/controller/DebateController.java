package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.service.ContentSafetyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AI 辩论", description = "多模型辩论赛：双方 AI 对同一个话题进行多轮辩论")
@RestController
@RequestMapping("/api/v1/debate")
public class DebateController {
    private final CoreClient coreClient;
    private final ContentSafetyService contentSafetyService;

    public DebateController(CoreClient coreClient, ContentSafetyService contentSafetyService) {
        this.coreClient = coreClient;
        this.contentSafetyService = contentSafetyService;
    }

    @Operation(summary = "发起辩论", description = "创建新辩论：内容安全检测 → 插入记录 → 触发 AI 辩论")
    @PostMapping
    public ResponseEntity<?> startDebate(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") != null ? body.get("req_id").toString() : UUID.randomUUID().toString();
        String question = body.get("question") != null ? body.get("question").toString() : "";
        Long userId = body.get("user_id") == null ? 0L : Long.parseLong(body.get("user_id").toString());

        String safetyResult = contentSafetyService.detectSensitive(question);
        if (safetyResult != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", contentSafetyService.getLabelHint(safetyResult)
            ));
        }

        // 插入辩论记录
        Map<String, Object> record = new HashMap<>();
        record.put("userId", userId);
        record.put("userName", "用户" + userId);
        record.put("question", question);
        record.put("status", "debating");
        coreClient.insertDebateRecord(record);

        // 插入消息记录
        Map<String, Object> msg = new HashMap<>();
        msg.put("reqId", reqId);
        msg.put("userId", userId);
        msg.put("question", question);
        msg.put("status", "debating");
        msg.put("isPrivate", 0);
        coreClient.insertMessage(msg);

        // 调用核心服务启动辩论
        Map<String, Object> debatePayload = new HashMap<>();
        debatePayload.put("req_id", reqId);
        debatePayload.put("question", question);
        debatePayload.put("user_id", userId);
        // 树状模式：语义拆解 → 多视角并行辩论 → DAG 汇总
        String mode = body.get("mode") != null ? body.get("mode").toString() : "";
        debatePayload.put("mode", mode);
        // 辩论轮数：默认 3，范围 1-10（防滥用，与 chat-core 校验一致）
        int rounds = 3;
        if (body.get("rounds") != null) {
            try {
                rounds = Integer.parseInt(body.get("rounds").toString());
            } catch (NumberFormatException ignored) {
                // 非法值回退默认
            }
        }
        debatePayload.put("rounds", Math.max(1, Math.min(10, rounds)));
        coreClient.debateStart(debatePayload);

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "debating"));
    }
}
