package com.example.chat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FrontendErrorController 单元测试")
class FrontendErrorControllerTest {

    @Test
    @DisplayName("report 返回 ok=true")
    void reportShouldReturnOk() {
        FrontendErrorController controller = new FrontendErrorController();
        ResponseEntity<Map<String, Object>> result = controller.report(Map.of(
                "message", "测试错误",
                "stack", "Error at line 10",
                "url", "http://example.com",
                "userAgent", "Chrome"
        ));
        assertEquals(200, result.getStatusCodeValue());
        assertNotNull(result.getBody());
        assertEquals(true, result.getBody().get("ok"));
    }

    @Test
    @DisplayName("report 空 payload 也不崩溃")
    void reportShouldNotCrashWithEmptyPayload() {
        FrontendErrorController controller = new FrontendErrorController();
        ResponseEntity<Map<String, Object>> result = controller.report(Map.of());
        assertEquals(200, result.getStatusCodeValue());
        assertNotNull(result.getBody());
        assertEquals(true, result.getBody().get("ok"));
    }

    @Test
    @DisplayName("report 缺失字段也不崩溃")
    void reportShouldHandleMissingFields() {
        FrontendErrorController controller = new FrontendErrorController();
        ResponseEntity<Map<String, Object>> result = controller.report(Map.of("message", "only message"));
        assertEquals(200, result.getStatusCodeValue());
        assertNotNull(result.getBody());
        assertEquals(true, result.getBody().get("ok"));
    }
}
