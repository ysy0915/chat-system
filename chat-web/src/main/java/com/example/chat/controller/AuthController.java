package com.example.chat.controller;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.dto.LoginRequest;
import com.example.chat.dto.RegisterRequest;
import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "用户认证", description = "登录、注册与 JWT 令牌管理")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Operation(summary = "用户登录", description = "用户名 + 密码登录，返回 JWT Token 和用户信息")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body) {
        String username = body.getUsername();
        String password = body.getPassword();
        User u = userRepository.findByName(username);
        if (u == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        }
        if (!passwordEncoder.matches(password, u.passwordHash)) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        }
        String token = jwtUtil.generateToken(u.email, u.id, u.role);
        return ResponseEntity.ok(Map.of("access_token", token, "user", Map.of(
                "id", u.id, "name", u.name, "nickname", u.nickname, "email", u.email, "role", u.role
        )));
    }

    @Operation(summary = "用户注册", description = "注册新用户（用户名/密码/昵称），返回 JWT Token")
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest payload) {
        String trimmedUsername = payload.getUsername().trim();
        // 昵称可选，未填则默认使用用户名
        String trimmedNickname = (payload.getNickname() == null || payload.getNickname().isBlank())
                ? trimmedUsername : payload.getNickname().trim();

        if (userRepository.findByName(trimmedUsername) != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, "用户名已被占用"));
        }

        User user = new User();
        user.email = trimmedUsername + "@chat.local";
        user.name = trimmedUsername;
        user.nickname = trimmedNickname;
        user.passwordHash = passwordEncoder.encode(payload.getPassword());
        user.role = "user";
        userRepository.insert(user);

        String token = jwtUtil.generateToken(user.email, user.id, user.role);
        return ResponseEntity.status(201).body(Map.of("access_token", token, "user", Map.of(
                "id", user.id, "name", user.name, "nickname", user.nickname, "email", user.email, "role", user.role
        )));
    }
}
