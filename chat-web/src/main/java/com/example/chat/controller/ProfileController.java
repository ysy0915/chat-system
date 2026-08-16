package com.example.chat.controller;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.security.AuthUtils;
import com.example.chat.security.JwtUtil;
import com.example.chat.service.ProfileService;
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
    private final ProfileService profileService;
    private final JwtUtil jwtUtil;

    public ProfileController(ProfileService profileService, JwtUtil jwtUtil) {
        this.profileService = profileService;
        this.jwtUtil = jwtUtil;
    }

    @Operation(summary = "获取个人资料", description = "返回当前登录用户的信息（需 JWT）")
    @GetMapping
    public ResponseEntity<?> getProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = AuthUtils.extractUserId(authHeader, jwtUtil);
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return profileService.getProfile(userId);
    }

    @Operation(summary = "修改个人资料", description = "更新当前用户的昵称和用户名（需 JWT）")
    @PutMapping
    public ResponseEntity<?> updateProfile(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, String> body) {
        Long userId = AuthUtils.extractUserId(authHeader, jwtUtil);
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return profileService.updateProfile(userId, body);
    }
}
