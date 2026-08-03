package com.example.chat.controller;

import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public ProfileController(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping
    public ResponseEntity<?> getProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        User user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        return ResponseEntity.ok(Map.of(
                "id", user.id,
                "name", user.name != null ? user.name : "",
                "nickname", user.nickname != null ? user.nickname : "",
                "email", user.email != null ? user.email : "",
                "role", user.role != null ? user.role : "",
                "createdAt", user.createdAt != null ? user.createdAt.toString() : ""
        ));
    }

    @PutMapping
    public ResponseEntity<?> updateProfile(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, String> body) {
        Long userId = extractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        User user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }

        String nickname = body.get("nickname");
        String name = body.get("name");

        if (nickname != null) user.nickname = nickname.trim();
        if (name != null && !name.trim().isEmpty()) user.name = name.trim();

        userRepository.updateProfile(user);

        return ResponseEntity.ok(Map.of(
                "id", user.id,
                "name", user.name != null ? user.name : "",
                "nickname", user.nickname != null ? user.nickname : ""
        ));
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) return null;
        return jwtUtil.getUserId(token);
    }
}
