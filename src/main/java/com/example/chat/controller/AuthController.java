package com.example.chat.controller;

import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        User u = userRepository.findByName(username);
        if (u == null) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        if (!passwordEncoder.matches(password, u.passwordHash)) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        String token = jwtUtil.generateToken(u.email, u.id, u.role);
        return ResponseEntity.ok(Map.of("access_token", token, "user", Map.of(
                "id", u.id, "name", u.name, "nickname", u.nickname, "email", u.email, "role", u.role
        )));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        String nickname = payload.get("nickname");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }
        if (nickname == null || nickname.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "昵称不能为空"));
        }
        String trimmedUsername = username.trim();
        String trimmedNickname = nickname.trim();
        if (trimmedUsername.length() > 50 || password.length() > 100 || trimmedNickname.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名、昵称或密码过长"));
        }

        if (userRepository.findByName(trimmedUsername) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名已被占用"));
        }

        User user = new User();
        user.email = trimmedUsername + "@chat.local";
        user.name = trimmedUsername;
        user.nickname = trimmedNickname;
        user.passwordHash = passwordEncoder.encode(password);
        user.role = "user";
        userRepository.insert(user);

        String token = jwtUtil.generateToken(user.email, user.id, user.role);
        return ResponseEntity.status(201).body(Map.of("access_token", token, "user", Map.of(
                "id", user.id, "name", user.name, "nickname", user.nickname, "email", user.email, "role", user.role
        )));
    }
}
