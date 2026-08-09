package com.example.chat.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 单元测试
 * 覆盖：Token 生成、解析 userId/subject/role、校验合法 Token、拒绝篡改 Token
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        // 使用固定密钥（≥32字节）
        jwtUtil = new JwtUtil("test-secret-key-for-unit-testing-only!!", 3_600_000L);
    }

    @Test
    @DisplayName("生成 Token 不为空")
    void generateToken_notBlank() {
        String token = jwtUtil.generateToken("user@test.com", 1L, "USER");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("解析 userId 正确")
    void getUserId_correct() {
        String token = jwtUtil.generateToken("user@test.com", 42L, "USER");
        assertEquals(42L, jwtUtil.getUserId(token));
    }

    @Test
    @DisplayName("解析 subject 正确")
    void getSubject_correct() {
        String token = jwtUtil.generateToken("user@test.com", 1L, "USER");
        assertEquals("user@test.com", jwtUtil.getSubject(token));
    }

    @Test
    @DisplayName("解析 role 正确")
    void getRole_correct() {
        String token = jwtUtil.generateToken("admin@test.com", 1L, "ADMIN");
        assertEquals("ADMIN", jwtUtil.getRole(token));
    }

    @Test
    @DisplayName("合法 Token 校验通过")
    void validateToken_valid() {
        String token = jwtUtil.generateToken("user@test.com", 1L, "USER");
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    @DisplayName("非法 Token 校验失败")
    void validateToken_invalid() {
        assertFalse(jwtUtil.validateToken("this.is.not.a.valid.token"));
    }

    @Test
    @DisplayName("Token 已过期时校验失败")
    void validateToken_expired() {
        JwtUtil shortLivedJwt = new JwtUtil("test-secret-key-for-unit-testing-only!!", 1L);
        String token = shortLivedJwt.generateToken("user@test.com", 1L, "USER");
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        assertFalse(shortLivedJwt.validateToken(token));
    }

    @Test
    @DisplayName("篡改 Token 校验失败")
    void validateToken_tampered() {
        String token = jwtUtil.generateToken("user@test.com", 1L, "USER");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertFalse(jwtUtil.validateToken(tampered));
    }
}
