package com.example.chat.service;

import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProfileService 业务逻辑断言：用户不存在 404、资料查询与更新（昵称/用户名 trim 落库）。
 * 协议层 JWT 鉴权断言见 {@code ProfileControllerTest}。
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    private ProfileService service;

    @BeforeEach
    void setUp() {
        service = new ProfileService(userRepository);
    }

    @Test
    void getProfile_userNotFound_404() {
        when(userRepository.findById(5L)).thenReturn(null);

        ResponseEntity<?> resp = service.getProfile(5L);

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void getProfile_success_returnsUserInfo() {
        User u = new User();
        u.id = 5L;
        u.name = "alice";
        u.nickname = "小爱";
        u.email = "a@x.com";
        u.role = "user";
        when(userRepository.findById(5L)).thenReturn(u);

        ResponseEntity<?> resp = service.getProfile(5L);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(5L, body.get("id"));
        assertEquals("小爱", body.get("nickname"));
        assertEquals("alice", body.get("name"));
        assertEquals("user", body.get("role"));
    }

    @Test
    void updateProfile_userNotFound_404() {
        when(userRepository.findById(5L)).thenReturn(null);

        ResponseEntity<?> resp = service.updateProfile(5L, Map.of("nickname", "新昵称"));

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void updateProfile_trimsAndPersists() {
        User u = new User();
        u.id = 5L;
        u.name = "alice";
        u.nickname = "旧昵称";
        when(userRepository.findById(5L)).thenReturn(u);

        ResponseEntity<?> resp = service.updateProfile(5L,
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
