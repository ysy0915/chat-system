package com.example.chat.controller;

import com.example.chat.dto.LoginRequest;
import com.example.chat.dto.RegisterRequest;
import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthController 真实行为断言：
 * 登录（成功/密码错误/用户不存在）、注册（用户名占用/昵称默认用户名/昵称透传）。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(userRepository, passwordEncoder, jwtUtil);
    }

    private LoginRequest login(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private RegisterRequest register(String username, String password, String nickname) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setNickname(nickname);
        return req;
    }

    private User user(long id, String name, String nickname, String email, String role, String passwordHash) {
        User u = new User();
        u.id = id;
        u.name = name;
        u.nickname = nickname;
        u.email = email;
        u.role = role;
        u.passwordHash = passwordHash;
        return u;
    }

    @Test
    void login_success_returnsTokenAndUserInfo() {
        User u = user(1L, "alice", "小爱", "alice@chat.local", "user", "encoded");
        when(userRepository.findByName("alice")).thenReturn(u);
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken("alice@chat.local", 1L, "user")).thenReturn("jwt-token");

        ResponseEntity<?> resp = controller.login(login("alice", "secret"));

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertEquals("jwt-token", body.get("access_token"));
        Map<?, ?> userInfo = (Map<?, ?>) body.get("user");
        assertNotNull(userInfo);
        assertEquals(1L, userInfo.get("id"));
        assertEquals("alice", userInfo.get("name"));
        assertEquals("小爱", userInfo.get("nickname"));
        assertEquals("alice@chat.local", userInfo.get("email"));
        assertEquals("user", userInfo.get("role"));
    }

    @Test
    void login_wrongPassword_401() {
        User u = user(1L, "alice", null, "a@x.com", "user", "encoded");
        when(userRepository.findByName("alice")).thenReturn(u);
        when(passwordEncoder.matches("bad", "encoded")).thenReturn(false);

        ResponseEntity<?> resp = controller.login(login("alice", "bad"));

        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void login_userNotFound_401() {
        when(userRepository.findByName("ghost")).thenReturn(null);

        ResponseEntity<?> resp = controller.login(login("ghost", "x"));

        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void register_usernameTaken_400() {
        when(userRepository.findByName("alice"))
                .thenReturn(user(1L, "alice", null, null, "user", "h"));

        ResponseEntity<?> resp = controller.register(register("alice", "secret", null));

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void register_success_blankNicknameDefaultsToUsername() {
        when(userRepository.findByName("alice")).thenReturn(null);
        when(passwordEncoder.encode("secret")).thenReturn("hash");
        // 模拟 MyBatis @Options 回填自增主键，否则响应 Map.of 遇 null id 抛 NPE
        when(userRepository.insert(any(User.class))).thenAnswer(inv -> {
            inv.<User>getArgument(0).id = 42L;
            return 1;
        });
        when(jwtUtil.generateToken("alice@chat.local", 42L, "user")).thenReturn("tok");

        ResponseEntity<?> resp = controller.register(register(" alice ", "secret", null));

        assertEquals(201, resp.getStatusCode().value());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("alice", saved.name);
        assertEquals("alice", saved.nickname);
        assertEquals("alice@chat.local", saved.email);
        assertEquals("user", saved.role);
        assertEquals("hash", saved.passwordHash);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertEquals("tok", body.get("access_token"));
    }

    @Test
    void register_success_keepsProvidedNicknameTrimmed() {
        when(userRepository.findByName("bob")).thenReturn(null);
        when(passwordEncoder.encode("secret")).thenReturn("hash");
        when(userRepository.insert(any(User.class))).thenAnswer(inv -> {
            inv.<User>getArgument(0).id = 42L;
            return 1;
        });
        // 响应 Map.of 不允许 null 值，token 必须 stub 否则 NPE
        when(jwtUtil.generateToken("bob@chat.local", 42L, "user")).thenReturn("tok");

        controller.register(register("bob", "secret", " 波波 "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insert(captor.capture());
        assertEquals("波波", captor.getValue().nickname);
    }
}
