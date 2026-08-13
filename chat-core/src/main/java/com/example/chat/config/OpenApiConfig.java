package com.example.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatCoreOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chat System - Core Internal API")
                        .description("核心AI服务内部API，供 chat-web 调用，包含消息处理、模型路由、RAG检索等")
                        .version("v1.0.0"));
    }
}
