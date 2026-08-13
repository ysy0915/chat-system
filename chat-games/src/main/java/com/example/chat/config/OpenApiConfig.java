package com.example.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatGamesOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chat System - Games API")
                        .description("游戏服务 API，提供城堡攻防等游戏接口")
                        .version("v1.0.0"));
    }
}
