package com.example.chat.controller;

import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 单元测试（手写 Stub，兼容 Java 26）
 * 覆盖：登录成功/失败、注册成功/用户名重复/参数缺失
 */
class AuthControllerTest {

    // ── 手写 UserRepository Stub ──────────────────────────────────────────────
    private final AtomicReference<User> stubUser = new AtomicReference<>();
    private final AtomicReference<Integer> insertedCount = new AtomicReference<>(0);

    private final UserRepository userRepoStub = new UserRepository() {
        @Override public User findByEmail(String email)     { return null; }
        @Override public User findByGuestName(String g)     { return null; }
        @Override public int updateProfile(User u)          { return 1; }
        @Override public int updateRegister(User u)         { return 1; }
        @Override public User findById(Long id)             { return null; }

        @Override
        public User findByName(String name) {
            User u = stubUser.get();
            return (u != null && name.equals(u.name)) ? u : null;
        }

        @Override
        public int insert(User u) {
            u.id = 999L;
            stubUser.set(u);
            insertedCount.set(insertedCount.get() + 1);
            return 1;
        }
    };

    // ── 真实 JwtUtil（使用固定测试密钥，32字节以上）────────────────────────────
    private final JwtUtil jwtUtil = new JwtUtil(
            "test-secret-key-32bytes-minimum!!", 3_600_000L);

    private PasswordEncoder passwordEncoder;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        stubUser.set(null);
        insertedCount.set(0);
        passwordEncoder = new BCryptPasswordEncoder();
        AuthController controller = new AuthController(userRepoStub, passwordEncoder, jwtUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilter(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    // ────────────── 登录 ──────────────

    @Test
    @DisplayName("POST /login：用户名不存在返回 401")
    void login_userNotFound_401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "nobody", "password", "123456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /login：密码错误返回 401")
    void login_wrongPassword_401() throws Exception {
        User user = buildUser(1L, "alice", "Alice", passwordEncoder.encode("correct"));
        stubUser.set(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /login：用户名密码正确返回 200 + access_token")
    void login_success_200() throws Exception {
        String rawPwd = "myPassword123";
        User user = buildUser(1L, "alice", "Alice", passwordEncoder.encode(rawPwd));
        stubUser.set(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "password", rawPwd))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("alice"));
    }

    // ────────────── 注册 ──────────────

    @Test
    @DisplayName("POST /register：用户名为空返回 400")
    void register_emptyUsername_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "", "password", "123456", "nickname", "测试"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /register：昵称为空返回 400")
    void register_emptyNickname_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "user1", "password", "123456", "nickname", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /register：用户名重复返回 400")
    void register_duplicateUsername_400() throws Exception {
        stubUser.set(buildUser(1L, "alice", "Alice", "hash"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "password", "123456", "nickname", "小爱"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("用户名已被占用"));
    }

    @Test
    @DisplayName("POST /register：正常注册返回 201 + access_token")
    void register_success_201() throws Exception {
        // stubUser 为空，表示该用户名未注册
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "newuser", "password", "Pass@123", "nickname", "新用户"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("newuser"));
    }

    // ────────────── 工具方法 ──────────────

    private User buildUser(Long id, String name, String nickname, String passwordHash) {
        User u = new User();
        u.id = id; u.name = name; u.nickname = nickname;
        u.email = name + "@chat.local";
        u.passwordHash = passwordHash; u.role = "user";
        return u;
    }
}
