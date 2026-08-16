package com.example.chat.controller;

import com.example.chat.security.AuthUtils;
import com.example.chat.security.JwtUtil;
import com.example.chat.service.ProfileService;
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

/**
 * ProfileController 协议层断言：JWT 鉴权（无效 token → 401，不触达业务 Service）。
 * 业务逻辑断言见 {@code ProfileServiceTest}。
 */
@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @Mock
    private ProfileService profileService;
    @Mock
    private JwtUtil jwtUtil;

    private ProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new ProfileController(profileService, jwtUtil);
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
    void updateProfile_noAuth_401() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(anyString(), any(JwtUtil.class))).thenReturn(null);

            ResponseEntity<?> resp = controller.updateProfile("Bearer invalid-token", Map.of("nickname", "新昵称"));

            assertEquals(401, resp.getStatusCode().value());
            verify(profileService, org.mockito.Mockito.never()).updateProfile(any(), any());
        }
    }
}
