package com.example.chat.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OssService 类存在性和结构验证测试。
 * OssService 需要 @ConditionalOnClass("com.aliyun.oss.OSS") 条件，
 * 在单元测试环境中无法实例化。
 */
class OssServiceTest {

    @Test
    void shouldHaveOssServiceClass() {
        Class<?> clazz = OssService.class;
        assertNotNull(clazz, "OssService class should exist");
    }

    @Test
    void shouldHaveTransferToOssMethod() throws Exception {
        Method method = OssService.class.getDeclaredMethod("transferToOss", String.class, String.class);
        assertNotNull(method, "transferToOss method should exist");
    }

    @Test
    void shouldHaveRefreshSignedUrlMethod() throws Exception {
        Method method = OssService.class.getDeclaredMethod("refreshSignedUrl", String.class);
        assertNotNull(method, "refreshSignedUrl method should exist");
    }

    @Test
    void shouldHaveServiceAnnotation() {
        assertTrue(OssService.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
                "OssService should have @Service annotation");
    }

    @Test
    void shouldHaveConstructor() {
        Constructor<?>[] constructors = OssService.class.getDeclaredConstructors();
        assertTrue(constructors.length > 0, "OssService should have constructors");
    }
}
