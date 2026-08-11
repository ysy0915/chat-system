package com.example.chat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtAuthenticationFilter 单元测试
 * 覆盖：无 Authorization 头直接放行、无效 Token 不设认证、有效 Token 设置 SecurityContext、
 * 非 Bearer 头忽略、设置请求属性 uid
 */
class JwtAuthenticationFilterTest {

    // 使用 ≥32 字节的固定密钥
    private static final String SECRET = "test-secret-key-for-unit-testing-01!!";
    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private boolean filterCalled;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 3_600_000L);
        filter = new JwtAuthenticationFilter(jwtUtil);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterCalled = false;
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("无 Authorization 头时直接放行，不设认证")
    void doFilterInternal_noAuthHeader_passesThrough() throws Exception {
        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getAttribute("uid"));
    }

    @Test
    @DisplayName("有效 Token 设置 SecurityContext 中的认证信息")
    void doFilterInternal_validToken_setsAuthentication() throws Exception {
        String token = jwtUtil.generateToken("user@test.com", 42L, "USER");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getCredentials());
    }

    @Test
    @DisplayName("有效 Token 设置请求属性 uid")
    void doFilterInternal_validToken_setsUidAttribute() throws Exception {
        String token = jwtUtil.generateToken("user@test.com", 99L, "USER");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertEquals(99L, request.getAttribute("uid"));
    }

    @Test
    @DisplayName("无效 Token 不设认证，正常放行")
    void doFilterInternal_invalidToken_passesThroughWithoutAuth() throws Exception {
        request.addHeader("Authorization", "Bearer this.is.not.a.valid.jwt.token");

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("非 Bearer Token 忽略（不设认证）")
    void doFilterInternal_nonBearerToken_ignored() throws Exception {
        String token = jwtUtil.generateToken("user@test.com", 1L, "USER");
        request.addHeader("Authorization", "Basic " + token);

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Authorization 头为空字符串时忽略")
    void doFilterInternal_emptyAuthHeader_ignored() throws Exception {
        request.addHeader("Authorization", "");

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("ADMIN 角色 Token 正确设置权限")
    void doFilterInternal_adminRole_setsRolePrefix() throws Exception {
        String token = jwtUtil.generateToken("admin@test.com", 1L, "ADMIN");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertEquals(1, authorities.size());
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("已带 ROLE_ 前缀的角色不重复添加前缀")
    void doFilterInternal_roleWithPrefix_notDuplicated() throws Exception {
        String token = jwtUtil.generateToken("user@test.com", 1L, "ROLE_CUSTOM");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOM")));
    }

    @Test
    @DisplayName("无 role 的 Token 默认给 ROLE_USER")
    void doFilterInternal_nullRole_defaultsToRoleUser() throws Exception {
        // Generate token with null role
        String token = jwtUtil.generateToken("user@test.com", 1L, null);
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }
}
