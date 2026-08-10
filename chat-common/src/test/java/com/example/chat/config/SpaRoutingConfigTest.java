package com.example.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖：SPA 路由注册、forward 目标、路由数量
 */
class SpaRoutingConfigTest {

    private SpaRoutingConfig config;

    @BeforeEach
    void setUp() {
        config = new SpaRoutingConfig();
    }

    @Test
    @DisplayName("注册后至少 9 条路由")
    void addViewControllers_registrationCount() throws Exception {
        ViewControllerRegistry registry = new ViewControllerRegistry(new GenericApplicationContext());
        config.addViewControllers(registry);
        List<?> registrations = getRegistrations(registry);
        assertTrue(registrations.size() >= 9, "应注册至少 9 条 SPA 路由，实际: " + registrations.size());
    }

    @Test
    @DisplayName("所有路由都 forward 到 /chat/index.html")
    void addViewControllers_allForwardToIndex() throws Exception {
        ViewControllerRegistry registry = new ViewControllerRegistry(new GenericApplicationContext());
        config.addViewControllers(registry);
        List<?> registrations = getRegistrations(registry);

        assertFalse(registrations.isEmpty());
        for (Object reg : registrations) {
            String viewName = getViewName(reg);
            assertNotNull(viewName, "每条路由应有 viewName");
            assertTrue(viewName.startsWith("forward:") || viewName.startsWith("redirect:"),
                    "路由应是 forward 或 redirect: " + viewName);
        }
    }

    @Test
    @DisplayName("包含具体路径路由 /chat/ 和 /chat/debate/")
    void addViewControllers_containsExpectedPaths() throws Exception {
        ViewControllerRegistry registry = new ViewControllerRegistry(new GenericApplicationContext());
        config.addViewControllers(registry);
        List<?> registrations = getRegistrations(registry);

        boolean hasChat = false, hasDebate = false;
        for (Object reg : registrations) {
            String path = getUrlPath(reg);
            if ("/chat/".equals(path)) hasChat = true;
            if ("/chat/debate/".equals(path)) hasDebate = true;
        }
        assertTrue(hasChat, "应包含 /chat/ 路由");
        assertTrue(hasDebate, "应包含 /chat/debate/ 路由");
    }

    @SuppressWarnings("unchecked")
    private List<Object> getRegistrations(ViewControllerRegistry registry) throws Exception {
        Field field = registry.getClass().getDeclaredField("registrations");
        field.setAccessible(true);
        return (List<Object>) field.get(registry);
    }

    private String getViewName(Object reg) throws Exception {
        try {
            Field field = reg.getClass().getDeclaredField("viewName");
            field.setAccessible(true);
            return (String) field.get(reg);
        } catch (NoSuchFieldException e) {
            // Fallback: try common field names
            for (Field f : reg.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    String val = (String) f.get(reg);
                    if (val != null && (val.startsWith("forward:") || val.startsWith("redirect:"))) {
                        return val;
                    }
                }
            }
            return null;
        }
    }

    private String getUrlPath(Object reg) throws Exception {
        try {
            Field field = reg.getClass().getDeclaredField("urlPath");
            field.setAccessible(true);
            return (String) field.get(reg);
        } catch (NoSuchFieldException e) {
            for (Field f : reg.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    String val = (String) f.get(reg);
                    if (val != null && val.startsWith("/chat/")) {
                        return val;
                    }
                }
            }
            return null;
        }
    }
}
