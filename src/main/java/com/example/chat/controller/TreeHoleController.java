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

    /** 发送情绪内容，获取 AI 回应 */
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
            TreeHoleMessage result = treeHoleService.askAndSave(userId, question, mood);
            return ResponseEntity.ok(result);
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
