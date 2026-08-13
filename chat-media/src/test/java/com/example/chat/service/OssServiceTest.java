package com.example.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OssService 真实行为断言（确定性降级路径，不依赖真实 OSS 客户端）：
 * 未启用/客户端未初始化时 transferToOss 原样返回；
 * 第三方域名不签名刷新（防 404 关键行为）；未启用 init 不崩溃。
 */
class OssServiceTest {

    private final OssService ossService = new OssService();

    @Test
    void transferToOss_disabled_returnsSourceUrl() {
        ReflectionTestUtils.setField(ossService, "enabled", false);

        String url = "https://third-party/static/file.png";
        assertEquals(url, ossService.transferToOss(url, "image"));
    }

    @Test
    void transferToOss_enabledButClientNotInit_returnsSourceUrl() {
        // enabled=true 但 ossClient 未初始化（init 未执行）→ 原样返回
        ReflectionTestUtils.setField(ossService, "enabled", true);

        String url = "https://third-party/static/file.png";
        assertEquals(url, ossService.transferToOss(url, "image"));
    }

    @Test
    void transferToOss_blankSource_returnsBlank() {
        ReflectionTestUtils.setField(ossService, "enabled", true);

        assertEquals("", ossService.transferToOss("", "image"));
        assertEquals("  ", ossService.transferToOss("  ", "video"));
    }

    @Test
    void refreshSignedUrl_disabled_returnsStoredUrl() {
        ReflectionTestUtils.setField(ossService, "enabled", false);

        String url = "https://bucket.oss-cn-shanghai.aliyuncs.com/media/image/2026-08-07/uuid.png?x=1";
        assertEquals(url, ossService.refreshSignedUrl(url));
    }

    @Test
    void refreshSignedUrl_thirdPartyHost_notSigned() {
        // 第三方域名（非本项目 bucket）→ 原样返回，避免用本项目密钥签名第三方 key
        ReflectionTestUtils.setField(ossService, "enabled", true);
        ReflectionTestUtils.setField(ossService, "bucketName", "my-chat-bucket");

        String thirdParty = "https://dashscope-result.oss-accelerate.aliyuncs.com/xxx.png";
        assertEquals(thirdParty, ossService.refreshSignedUrl(thirdParty));
    }

    @Test
    void init_disabled_noCrash() {
        ReflectionTestUtils.setField(ossService, "enabled", false);
        assertDoesNotThrow(() -> ossService.init());
    }
}
