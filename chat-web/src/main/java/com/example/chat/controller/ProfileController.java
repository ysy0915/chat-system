package com.example.chat.controller;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.AuthUtils;
import com.example.chat.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "个人中心", description = "查看和修改个人资料（需 JWT 认证）")
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public ProfileController(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    @Operation(summary = "获取个人资料", description = "返回当前登录用户的信息（需 JWT）")
    @GetMapping
    public ResponseEntity<?> getProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = AuthUtils.extractUserId(authHeader, jwtUtil);
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        User user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(ErrorCode.NOT_FOUND, "用户不存在"));
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

    @Operation(summary = "修改个人资料", description = "更新当前用户的昵称和用户名（需 JWT）")
    @PutMapping
    public ResponseEntity<?> updateProfile(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, String> body) {
        Long userId = AuthUtils.extractUserId(authHeader, jwtUtil);
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        User user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(ErrorCode.NOT_FOUND, "用户不存在"));
        }

        String nickname = body.get("nickname");
        String name = body.get("name");

        if (nickname != null) user.nickname = nickname.trim();
        if (name != null && !name.trim().isBlank()) user.name = name.trim();

        userRepository.updateProfile(user);

        return ResponseEntity.ok(Map.of(
                "id", user.id,
                "name", user.name != null ? user.name : "",
                "nickname", user.nickname != null ? user.nickname : ""
        ));
    }
}
