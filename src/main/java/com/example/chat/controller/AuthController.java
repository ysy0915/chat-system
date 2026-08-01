package com.example.chat.controller;

import com.example.chat.entity.User;
import com.example.chat.entity.UserRegistration;
import com.example.chat.repository.UserRepository;
import com.example.chat.repository.UserRegistrationRepository;
import com.example.chat.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final UserRegistrationRepository registrationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository,
                          UserRegistrationRepository registrationRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.registrationRepository = registrationRepository;
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
        return ResponseEntity.ok(Map.of("access_token", token, "user", u));
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        String username = payload.get("username");
        String password = payload.get("password");

        if (userRepository.findByEmail(email) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "邮箱已被注册"));
        }
        if (userRepository.findByName(username) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名已被占用"));
        }

        User user = new User();
        user.email = email;
        user.name = username;
        user.passwordHash = passwordEncoder.encode(password);
        user.role = "user";
        userRepository.insert(user);

        UserRegistration reg = new UserRegistration();
        reg.userId = user.id;
        reg.email = email;
        reg.username = username;
        registrationRepository.insert(reg);

        String token = jwtUtil.generateToken(user.email, user.id, user.role);
        return ResponseEntity.status(201).body(Map.of("access_token", token, "user", user));
    }
}
