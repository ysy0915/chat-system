package com.example.chat.config;

import com.example.chat.security.IpRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ViewConfig implements WebMvcConfigurer {

    private final IpRateLimitInterceptor ipRateLimitInterceptor;

    public ViewConfig(IpRateLimitInterceptor ipRateLimitInterceptor) {
        this.ipRateLimitInterceptor = ipRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ipRateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/chat/assets/**",
                        "/chat/index.html",
                        "/favicon.ico",
                        "/actuator/**",
                        "/api/v1/llm/admin/**"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/chat/assets/**")
                .addResourceLocations("classpath:/static/chat/assets/")
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward all /chat/** routes to index.html for SPA routing
        registry.addViewController("/chat").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/home").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/games").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/games/pingpong").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/debate").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/personal").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/graph").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/media").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/history").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/profile").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/admin/models").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/sql").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/monitor").setViewName("forward:/chat/index.html");
    }
}
