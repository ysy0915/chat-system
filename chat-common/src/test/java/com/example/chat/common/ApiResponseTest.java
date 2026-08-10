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
}
