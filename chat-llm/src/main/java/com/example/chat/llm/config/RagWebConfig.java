package com.example.chat.llm.config;

import com.example.chat.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * RAG 知识库管理 Web 配置：注册 {@link RagAdminAuthInterceptor} 到
 * {@code /api/v1/rag/**}（仅 {@code KnowledgeController} 的路径）。
 *
 * <p>注意：{@code /internal/**}（内部 API）与 standalone 模式不经过本拦截器。</p>
 */
@Configuration
public class RagWebConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    /** 安全开关（standalone 模式为 false，拦截器放行） */
    @Value("${app.security.enabled:true}")
    private boolean securityEnabled;

    public RagWebConfig(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Bean
    public RagAdminAuthInterceptor ragAdminAuthInterceptor() {
        return new RagAdminAuthInterceptor(jwtUtil, objectMapper, securityEnabled);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ragAdminAuthInterceptor())
                .addPathPatterns("/api/v1/rag/**");
    }
}
