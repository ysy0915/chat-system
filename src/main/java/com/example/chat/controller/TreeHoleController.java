package com.example.chat.controller;

import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.security.JwtUtil;
import com.example.chat.service.TreeHoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/treehole")
public class TreeHoleController {

    private final TreeHoleService treeHoleService;
    private final JwtUtil jwtUtil;

    public TreeHoleController(TreeHoleService treeHoleService, JwtUtil jwtUtil) {
        this.treeHoleService = treeHoleService;
        this.jwtUtil = jwtUtil;
    }

    /** 获取当前用户的树洞历史记录 */
    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        List<TreeHoleMessage> list = treeHoleService.getHistory(userId);
        return ResponseEntity.ok(list);
    }

    /** 树洞：最近 5 条历史（页面初始化） */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(treeHoleService.getRecentHistory(userId, 5));
    }

    /** 树洞：搜索历史（分页） */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("keyword") String keyword,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "size", defaultValue = "5") int size,
                                     HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        int offset = (page - 1) * size;
        List<TreeHoleMessage> items = treeHoleService.searchHistory(userId, keyword, offset, size);
        int total = treeHoleService.countSearchHistory(userId, keyword);
        return ResponseEntity.ok(Map.of("items", items, "total", total, "page", page, "size", size, "totalPages", (total + size - 1) / size));
    }

    /** 树洞：获取某条记录前后 5 条上下文 */
    @GetMapping("/context")
    public ResponseEntity<?> context(@RequestParam("msg_id") Long msgId, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");
        return ResponseEntity.ok(treeHoleService.getContextAround(userId, msgId));
    }

    /** 发送情绪内容，流式返回 AI 回应（通过 WebSocket 推送） */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");

        String question = body.get("question");
        String mood = body.getOrDefault("mood", "");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body("内容不能为空");
        }
        try {
            treeHoleService.askAndStream(userId, question, mood);
            return ResponseEntity.ok(Map.of("status", "streaming"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** 带文件的树洞请求（文件解析 / 生成文档 / 生成PPT，由智谱完成） */
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
            TreeHoleMessage result = treeHoleService.askWithFile(
                    userId, question, mood, file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("文件处理失败: " + e.getMessage());
        }
    }

    /** 重新生成：根据原 reqId 重新调用 AI 生成回答 */
    @PostMapping("/regenerate")
    public ResponseEntity<?> regenerate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");

        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body("req_id 不能为空");
        }
        try {
            treeHoleService.regenerate(reqId, userId);
            return ResponseEntity.accepted().body(Map.of("status", "regenerating", "old_req_id", reqId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("重新生成失败: " + e.getMessage());
        }
    }

    /** 停止生成：通知后端中断指定 reqId 的流式输出 */
    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) return ResponseEntity.status(401).body("未登录");

        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body("req_id 不能为空");
        }
        treeHoleService.requestStop(reqId);
        return ResponseEntity.ok(Map.of("status", "stopped", "req_id", reqId));
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
