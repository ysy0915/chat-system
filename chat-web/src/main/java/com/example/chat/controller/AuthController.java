package com.example.chat.controller;

import com.example.chat.dto.LoginRequest;
import com.example.chat.dto.RegisterRequest;
import com.example.chat.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户认证", description = "登录、注册与 JWT 令牌管理")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "获取注册验证码", description = "生成一次性算术验证码（5 分钟有效），注册时需携带")
    @GetMapping("/captcha")
    public ResponseEntity<?> captcha() {
        return ResponseEntity.ok(authService.generateCaptcha());
    }

    @Operation(summary = "用户登录", description = "用户名 + 密码登录，返回 JWT Token 和用户信息（连续失败 5 次锁定 15 分钟）")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return authService.login(body, request);
    }

    @Operation(summary = "用户注册", description = "验证码 + 用户名/密码/昵称注册，返回 JWT Token（防自动化刷号）")
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest payload, HttpServletRequest request) {
        return authService.register(payload, request);
    }
}
