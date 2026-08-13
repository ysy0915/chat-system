package com.example.chat.controller;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.dto.LoginRequest;
import com.example.chat.dto.RegisterRequest;
import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import com.example.chat.security.RateLimitChecker;
import com.example.chat.service.CaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@Tag(name = "用户认证", description = "登录、注册与 JWT 令牌管理")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final int LOGIN_LOCK_THRESHOLD = 5;
    private static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(15);
    private static final int REGISTER_PER_HOUR_LIMIT = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final CaptchaService captchaService;
    private final RateLimitChecker rateLimitChecker;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          CaptchaService captchaService,
                          RateLimitChecker rateLimitChecker) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.captchaService = captchaService;
        this.rateLimitChecker = rateLimitChecker;
    }

    /** 解析客户端真实 IP（Nginx 反代后取 X-Real-IP / X-Forwarded-For） */
    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                ip = xff.split(",")[0].trim();
            }
        }
        return ip == null || ip.isBlank() ? request.getRemoteAddr() : ip;
    }

    @Operation(summary = "获取注册验证码", description = "生成一次性算术验证码（5 分钟有效），注册时需携带")
    @GetMapping("/captcha")
    public ResponseEntity<?> captcha() {
        return ResponseEntity.ok(captchaService.generate());
    }

    @Operation(summary = "用户登录", description = "用户名 + 密码登录，返回 JWT Token 和用户信息（连续失败 5 次锁定 15 分钟）")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String username = body.getUsername();
        String password = body.getPassword();

        // 登录失败锁定：同一 IP 连续失败 5 次锁定 15 分钟（防暴力破解 + 用户名枚举）
        String lockKey = "rate:login-fail:" + clientIp(request);
        if (rateLimitChecker.getCount(lockKey) >= LOGIN_LOCK_THRESHOLD) {
            return ResponseEntity.status(429).body(ApiResponse.error(ErrorCode.RATE_LIMITED,
                    "登录失败次数过多，请15分钟后再试"));
        }

        User u = userRepository.findByName(username);
        // 统一错误文案：不区分"用户不存在"与"密码错误"，防用户名枚举
        if (u == null || !passwordEncoder.matches(password, u.passwordHash)) {
            rateLimitChecker.checkAndIncrement(lockKey, LOGIN_LOCK_THRESHOLD, LOGIN_LOCK_DURATION);
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        }

        rateLimitChecker.reset(lockKey);
        String token = jwtUtil.generateToken(u.email, u.id, u.role);
        return ResponseEntity.ok(Map.of("access_token", token, "user", Map.of(
                "id", u.id, "name", u.name, "nickname", u.nickname, "email", u.email, "role", u.role
        )));
    }

    @Operation(summary = "用户注册", description = "验证码 + 用户名/密码/昵称注册，返回 JWT Token（防自动化刷号）")
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest payload, HttpServletRequest request) {
        // 1. 验证码校验（防自动化批量刷号 / 用户名枚举）
        if (!captchaService.verify(payload.getCaptchaToken(), payload.getCaptchaAnswer())) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, "验证码错误或已过期"));
        }

        // 2. IP 级注册频率限制（兜底防刷）
        String ip = clientIp(request);
        if (!rateLimitChecker.checkAndIncrement("rate:register:" + ip, REGISTER_PER_HOUR_LIMIT, Duration.ofHours(1))) {
            return ResponseEntity.status(429).body(ApiResponse.error(ErrorCode.RATE_LIMITED, "注册过于频繁，请稍后再试"));
        }

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
