package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Tag(name = "树洞", description = "匿名/半匿名树洞消息：提问、文件、重新生成、停止")
@RestController
@RequestMapping("/api/v1/treehole")
public class TreeHoleController {

    private final CoreClient coreClient;
    private final JwtUtil jwtUtil;

    public TreeHoleController(CoreClient coreClient, JwtUtil jwtUtil) {
        this.coreClient = coreClient;
        this.jwtUtil = jwtUtil;
    }

    @Operation(summary = "历史消息", description = "获取当前用户的历史树洞消息")
    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        // history 接口用 recent 代替（返回全部）
        return ResponseEntity.ok(coreClient.treeHoleRecent(userId));
    }

    @Operation(summary = "最近消息", description = "获取当前用户最近的树洞消息")
    @GetMapping("/recent")
    public ResponseEntity<?> recent(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(coreClient.treeHoleRecent(userId));
    }

    @Operation(summary = "搜索树洞消息", description = "按关键词搜索树洞消息，支持分页")
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("keyword") String keyword,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "size", defaultValue = "5") int size,
                                     HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(coreClient.treeHoleSearch(userId, keyword, page, size));
    }

    @Operation(summary = "上下文消息", description = "获取某条树洞消息的对话上下文")
    @GetMapping("/context")
    public ResponseEntity<?> context(@RequestParam("msg_id") Long msgId, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(coreClient.treeHoleContext(userId, msgId));
    }

    @Operation(summary = "树洞提问", description = "向树洞发送问题并触发 AI 流式回答")
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");

        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body("内容不能为空");
        }
        String reqId = java.util.UUID.randomUUID().toString();
        try {
            coreClient.treeHoleAsk(userId, question, reqId);
            return ResponseEntity.ok(Map.of("status", "streaming", "req_id", reqId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "树洞带文件提问", description = "上传图片/文档后向树洞提问")
    @PostMapping("/ask-with-file")
    public ResponseEntity<?> askWithFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "question", required = false, defaultValue = "") String question,
            @RequestParam(value = "mood", required = false, defaultValue = "") String mood,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body("未上传文件");
        try {
            String reqId = java.util.UUID.randomUUID().toString();
            coreClient.treeHoleAskWithFile(userId, question, reqId,
                    file.getOriginalFilename(), file.getBytes(), file.getContentType());
            return ResponseEntity.ok(Map.of("status", "streaming", "req_id", reqId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body("文件处理失败: " + e.getMessage());
        }
    }

    @Operation(summary = "重新生成回答", description = "根据 req_id 触发树洞 AI 重新生成")
    @PostMapping("/regenerate")
    public ResponseEntity<?> regenerate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");

        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body("req_id 不能为空");
        }
        try {
            coreClient.treeHoleRegenerate(userId, reqId);
            return ResponseEntity.accepted().body(Map.of("status", "regenerating", "old_req_id", reqId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("重新生成失败: " + e.getMessage());
        }
    }

    @Operation(summary = "停止生成", description = "停止指定 req_id 的树洞 AI 生成")
    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        if (reqId != null && !reqId.isBlank()) {
            coreClient.treeHoleStop(reqId);
        }
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    private Long extractUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7);
        try {
            if (!jwtUtil.validateToken(token)) return null;
            return jwtUtil.getUserId(token);
        } catch (Exception e) {
            return null;
        }
    }
}
