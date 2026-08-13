package com.example.chat.config;

import com.example.chat.security.IpRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖：拦截器注册、资源处理器、视图控制器
 */
@ExtendWith(MockitoExtension.class)
class ViewConfigTest {

    @Mock
    private IpRateLimitInterceptor ipRateLimitInterceptor;

    private ViewConfig config;

    @BeforeEach
    void setUp() {
        config = new ViewConfig(ipRateLimitInterceptor);
    }

    @Test
    @DisplayName("addInterceptors 注册成功不抛异常")
    void addInterceptors_registersWithoutException() {
        InterceptorRegistry registry = new InterceptorRegistry();

        assertDoesNotThrow(() -> config.addInterceptors(registry));
    }

    @Test
    @DisplayName("addResourceHandlers 注册成功不抛异常")
    void addResourceHandlers_registersWithoutException() {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry(
                new GenericApplicationContext(), null);

        assertDoesNotThrow(() -> config.addResourceHandlers(registry));
    }

    @Test
    @DisplayName("addViewControllers 注册至少 10 条 SPA 路由")
    void addViewControllers_minimumRoutes() throws Exception {
        ViewControllerRegistry registry = new ViewControllerRegistry(new GenericApplicationContext());
        config.addViewControllers(registry);

        Field field = registry.getClass().getDeclaredField("registrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> registrations = (List<?>) field.get(registry);

        assertTrue(registrations.size() >= 10, "应注册至少 10 条视图路由，实际: " + registrations.size());
    }
}
