package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentSafetyServiceTest {

    private final ContentSafetyService service = new ContentSafetyService();

    @Test
    @DisplayName("detectSensitive 禁用时返回 null")
    void testDetectSensitive_disabled() {
        // enabled 默认为 false (因为 @Value 在纯单元测试中不会注入)
        assertNull(service.detectSensitive("测试文本"));
    }

    @Test
    @DisplayName("detectSensitive 空文本返回 null")
    void testDetectSensitive_nullText() {
        assertNull(service.detectSensitive(null));
        assertNull(service.detectSensitive(""));
        assertNull(service.detectSensitive("   "));
    }

    @Test
    @DisplayName("getLabelHint politics 标签")
    void testGetLabelHint_politics() {
        String hint = service.getLabelHint("politics");
        assertTrue(hint.contains("政治"));
    }

    @Test
    @DisplayName("getLabelHint pornography 标签")
    void testGetLabelHint_pornography() {
        String hint = service.getLabelHint("pornography");
        assertTrue(hint.contains("不适当"));
    }

    @Test
    @DisplayName("getLabelHint violence 标签")
    void testGetLabelHint_violence() {
        String hint = service.getLabelHint("violence");
        assertTrue(hint.contains("暴力"));
    }

    @Test
    @DisplayName("getLabelHint terror 标签")
    void testGetLabelHint_terror() {
        String hint = service.getLabelHint("terror");
        assertTrue(hint.contains("敏感"));
    }

    @Test
    @DisplayName("getLabelHint abuse 标签")
    void testGetLabelHint_abuse() {
        String hint = service.getLabelHint("abuse");
        assertTrue(hint.contains("不当"));
    }

    @Test
    @DisplayName("getLabelHint contraband 标签")
    void testGetLabelHint_contraband() {
        String hint = service.getLabelHint("contraband");
        assertTrue(hint.contains("违禁"));
    }

    @Test
    @DisplayName("getLabelHint null 标签返回默认提示")
    void testGetLabelHint_null() {
        String hint = service.getLabelHint(null);
        assertTrue(hint.contains("敏感"));
    }

    @Test
    @DisplayName("getLabelHint 未知标签返回默认提示")
    void testGetLabelHint_unknown() {
        String hint = service.getLabelHint("unknown_label");
        assertTrue(hint.contains("敏感"));
    }

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.service.ContentSafetyService");
        });
    }
}
