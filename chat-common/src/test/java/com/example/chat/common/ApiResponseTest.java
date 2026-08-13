package com.example.chat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    @DisplayName("ok 带数据")
    void testOkWithData() {
        Map<String, Object> r = ApiResponse.ok("result");
        assertEquals(true, r.get("ok"));
        assertEquals("result", r.get("data"));
    }

    @Test
    @DisplayName("ok 无数据")
    void testOkWithoutData() {
        Map<String, Object> r = ApiResponse.ok();
        assertEquals(true, r.get("ok"));
    }

    @Test
    @DisplayName("error 无 code")
    void testErrorWithoutCode() {
        Map<String, Object> r = ApiResponse.error("fail");
        assertEquals(false, r.get("ok"));
        assertEquals("fail", r.get("error"));
    }

    @Test
    @DisplayName("error 带 code")
    void testErrorWithCode() {
        Map<String, Object> r = ApiResponse.error(500, "internal");
        assertEquals(false, r.get("ok"));
        assertEquals(500, r.get("code"));
        assertEquals("internal", r.get("error"));
    }

    @Test
    @DisplayName("error 带 ErrorCode 自定义文案")
    void testErrorWithErrorCode() {
        Map<String, Object> r = ApiResponse.error(ErrorCode.RATE_LIMITED, "太频繁");
        assertEquals(false, r.get("ok"));
        assertEquals(429, r.get("code"));
        assertEquals("太频繁", r.get("error"));
    }

    @Test
    @DisplayName("error 带 ErrorCode 默认文案")
    void testErrorWithErrorCodeDefault() {
        Map<String, Object> r = ApiResponse.error(ErrorCode.FORBIDDEN);
        assertEquals(false, r.get("ok"));
        assertEquals(403, r.get("code"));
        assertEquals("权限不足", r.get("error"));
    }

    @Test
    @DisplayName("success / fail 语义别名")
    void testAliases() {
        assertEquals(true, ApiResponse.success().get("ok"));
        assertEquals("x", ApiResponse.success("x").get("data"));
        assertEquals(false, ApiResponse.fail("boom").get("ok"));
    }
}
