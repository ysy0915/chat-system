package com.example.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatWebOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chat System - Web API")
                        .description("智聊系统 Web 接入层 API 文档，提供用户认证、消息管理、树洞、辩论等接口")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("开发团队")
                                .email("dev@example.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
