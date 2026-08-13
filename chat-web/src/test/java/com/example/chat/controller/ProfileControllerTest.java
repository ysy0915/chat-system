package com.example.chat.controller;

import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.AuthUtils;
import com.example.chat.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProfileController 真实行为断言：
 * JWT 鉴权 401、用户不存在 404、资料查询与更新（昵称/用户名 trim 落库）。
 */
@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtUtil jwtUtil;

    private ProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new ProfileController(userRepository, jwtUtil);
    }

    @Test
    void getProfile_noAuth_401() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            // 无效 token → 提取 userId 为 null → 401
            mocked.when(() -> AuthUtils.extractUserId(anyString(), any(JwtUtil.class))).thenReturn(null);

            ResponseEntity<?> resp = controller.getProfile("Bearer invalid-token");

            assertEquals(401, resp.getStatusCode().value());
        }
    }

    @Test
    void getProfile_userNotFound_404() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(anyString(), any(JwtUtil.class))).thenReturn(5L);
            when(userRepository.findById(5L)).thenReturn(null);

            ResponseEntity<?> resp = controller.getProfile("Bearer token");

            assertEquals(404, resp.getStatusCode().value());
        }
    }

    @Test
    void getProfile_success_returnsUserInfo() {
        User u = new User();
        u.id = 5L;
        u.name = "alice";
        u.nickname = "小爱";
        u.email = "a@x.com";
        u.role = "user";
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(anyString(), any(JwtUtil.class))).thenReturn(5L);
            when(userRepository.findById(5L)).thenReturn(u);

            ResponseEntity<?> resp = controller.getProfile("Bearer token");

            assertEquals(200, resp.getStatusCode().value());
            Map<?, ?> body = (Map<?, ?>) resp.getBody();
            assertEquals(5L, body.get("id"));
            assertEquals("小爱", body.get("nickname"));
            assertEquals("alice", body.get("name"));
            assertEquals("user", body.get("role"));
        }
    }

    @Test
    void updateProfile_noAuth_401() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(anyString(), any(JwtUtil.class))).thenReturn(null);

            ResponseEntity<?> resp = controller.updateProfile("Bearer invalid-token", Map.of("nickname", "新昵称"));

            assertEquals(401, resp.getStatusCode().value());
            verify(userRepository, org.mockito.Mockito.never()).updateProfile(any());
        }
    }

    @Test
    void updateProfile_trimsAndPersists() {
        User u = new User();
        u.id = 5L;
        u.name = "alice";
        u.nickname = "旧昵称";
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(anyString(), any(JwtUtil.class))).thenReturn(5L);
            when(userRepository.findById(5L)).thenReturn(u);

            ResponseEntity<?> resp = controller.updateProfile("Bearer token",
                    Map.of("nickname", " 新昵称 ", "name", " newname "));

            assertEquals(200, resp.getStatusCode().value());
            assertEquals("新昵称", u.nickname);
            assertEquals("newname", u.name);
            verify(userRepository).updateProfile(u);
            Map<?, ?> body = (Map<?, ?>) resp.getBody();
            assertEquals("新昵称", body.get("nickname"));
            assertEquals("newname", body.get("name"));
        }
    }
}
