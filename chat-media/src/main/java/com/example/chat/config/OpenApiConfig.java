package com.example.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatMediaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chat System - Media API")
                        .description("多模态服务 API，提供 AI 图片生成、视频生成、3D 模型生成接口")
                        .version("v1.0.0"));
    }
}
