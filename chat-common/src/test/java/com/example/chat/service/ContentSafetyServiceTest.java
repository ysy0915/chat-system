package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentSafetyServiceTest {

    private final ContentSafetyService service = new ContentSafetyService();

    @Test
    @DisplayName("detectSensitive 禁用时 fail-close 返回 ERROR_LABEL")
    void testDetectSensitive_disabled() {
        // enabled 默认为 false（@Value 不注入），非空文本且阿里云不可用 → 严格 fail-close 拦截
        assertEquals(ContentSafetyService.ERROR_LABEL, service.detectSensitive("测试文本"));
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

    // ============ 本地敏感词预检 ============

    @Test
    @DisplayName("本地词库命中返回 local_blacklist 标签")
    void testLocalBlacklist_hit() {
        service.reloadLocalBlacklist("赌博,色情,违禁品");
        assertEquals(ContentSafetyService.LOCAL_BLACKLIST_LABEL,
                service.detectSensitive("这里可以赌博"));
    }

    @Test
    @DisplayName("本地词库未命中 + 阿里云不可用 → fail-close 返回 ERROR_LABEL")
    void testLocalBlacklist_miss() {
        service.reloadLocalBlacklist("赌博,色情");
        // 未命中本地词库，且 clientReady=false（无阿里云 client）→ 严格 fail-close 拦截
        assertEquals(ContentSafetyService.ERROR_LABEL, service.detectSensitive("今天天气不错"));
    }

    @Test
    @DisplayName("本地词库大小写不敏感匹配")
    void testLocalBlacklist_caseInsensitive() {
        service.reloadLocalBlacklist("GAMBLE");
        assertEquals(ContentSafetyService.LOCAL_BLACKLIST_LABEL,
                service.detectSensitive("let's gamble now"));
    }

    @Test
    @DisplayName("空词库 + 阿里云不可用 → fail-close 拦截")
    void testLocalBlacklist_empty() {
        service.reloadLocalBlacklist("");
        // 空词库未命中，但阿里云不可用 → fail-close
        assertEquals(ContentSafetyService.ERROR_LABEL, service.detectSensitive("正常文本"));
    }

    @Test
    @DisplayName("getLabelHint 本地词库标签")
    void testGetLabelHint_localBlacklist() {
        String hint = service.getLabelHint(ContentSafetyService.LOCAL_BLACKLIST_LABEL);
        assertTrue(hint.contains("敏感"));
    }
}
