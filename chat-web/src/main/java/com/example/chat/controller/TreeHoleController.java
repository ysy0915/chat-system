package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/treehole")
public class TreeHoleController {

    private final CoreClient coreClient;
    private final JwtUtil jwtUtil;

    public TreeHoleController(CoreClient coreClient, JwtUtil jwtUtil) {
        this.coreClient = coreClient;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        // history 接口用 recent 代替（返回全部）
        return ResponseEntity.ok(coreClient.treeHoleRecent(userId));
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(coreClient.treeHoleRecent(userId));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("keyword") String keyword,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "size", defaultValue = "5") int size,
                                     HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(coreClient.treeHoleSearch(userId, keyword, page, size));
    }

    @GetMapping("/context")
    public ResponseEntity<?> context(@RequestParam("msg_id") Long msgId, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(coreClient.treeHoleContext(userId, msgId));
    }

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
        } catch (Exception e) {
            return ResponseEntity.status(500).body("文件处理失败: " + e.getMessage());
        }
    }

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
