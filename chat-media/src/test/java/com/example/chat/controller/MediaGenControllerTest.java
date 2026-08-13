package com.example.chat.controller;

import com.example.chat.dto.MediaGenerateRequest;
import com.example.chat.repository.MediaGenRecordRepository;
import com.example.chat.security.AuthUtils;
import com.example.chat.service.MediaGenService;
import com.example.chat.service.OssService;
import com.example.chat.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MediaGenController 真实行为断言：
 * 3D 白名单访问控制、未登录 401、3D 未授权 403。
 */
@ExtendWith(MockitoExtension.class)
class MediaGenControllerTest {

    @Mock
    private MediaGenService mediaGenService;

    @Mock
    private MediaGenRecordRepository mediaGenRecordRepository;

    @Mock
    private OssService ossService;

    @Mock
    private RateLimitService rateLimitService;

    private MediaGenController controller;

    @BeforeEach
    void setUp() {
        controller = new MediaGenController(mediaGenService, mediaGenRecordRepository, ossService, rateLimitService);
    }

    @Test
    void check3DAccess_whitelistUser_allowed() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUsernameFromContext).thenReturn("雪梨");

            ResponseEntity<?> resp = controller.check3DAccess();

            assertEquals(200, resp.getStatusCode().value());
            Map<?, ?> body = (Map<?, ?>) resp.getBody();
            assertNotNull(body);
            assertEquals(true, body.get("allowed"));
            assertEquals("雪梨", body.get("username"));
        }
    }

    @Test
    void check3DAccess_nonWhitelistUser_denied() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUsernameFromContext).thenReturn("路人");

            ResponseEntity<?> resp = controller.check3DAccess();

            Map<?, ?> body = (Map<?, ?>) resp.getBody();
            assertNotNull(body);
            assertEquals(false, body.get("allowed"));
        }
    }

    @Test
    void check3DAccess_notLoggedIn_deniedWithEmptyUsername() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUsernameFromContext).thenReturn(null);

            ResponseEntity<?> resp = controller.check3DAccess();

            Map<?, ?> body = (Map<?, ?>) resp.getBody();
            assertNotNull(body);
            assertEquals(false, body.get("allowed"));
            assertEquals("", body.get("username"));
        }
    }

    @Test
    void getStatus_notLoggedIn_returns401() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUserIdFromContext).thenReturn(null);

            ResponseEntity<?> resp = controller.getStatus(1L);

            assertEquals(401, resp.getStatusCode().value());
        }
    }

    @Test
    void getHistory_notLoggedIn_returns401() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUserIdFromContext).thenReturn(null);

            ResponseEntity<?> resp = controller.getHistory(null, 20);

            assertEquals(401, resp.getStatusCode().value());
        }
    }

    @Test
    void generate_3dNotAllowed_returns403WithoutCallingService() {
        MediaGenerateRequest req = new MediaGenerateRequest();
        req.setPrompt("一个城堡");
        req.setType("3d");

        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUsernameFromContext).thenReturn("路人");

            ResponseEntity<?> resp = controller.generate(req);

            assertEquals(403, resp.getStatusCode().value());
            verify(mediaGenService, never()).generate(any(), any(), any());
        }
    }

    @Test
    void generate_notLoggedIn_returns401() {
        MediaGenerateRequest req = new MediaGenerateRequest();
        req.setPrompt("一只猫");
        req.setType("image");

        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUserIdFromContext).thenReturn(null);

            ResponseEntity<?> resp = controller.generate(req);

            assertEquals(401, resp.getStatusCode().value());
        }
    }
}
