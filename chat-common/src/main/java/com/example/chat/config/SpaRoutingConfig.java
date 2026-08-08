package com.example.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 路由配置
 * 让前端单页应用的路由（如 /chat/、/treehole/）正确返回 index.html
 */
@Configuration
public class SpaRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 前端页面路由映射到 index.html
        registry.addViewController("/chat/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/treehole/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/personal/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/debate/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/monitor/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/knowledge/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/media/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/history/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/games/").setViewName("forward:/chat/index.html");
    }
}
