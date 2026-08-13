package com.example.chat.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatCommonOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chat System API")
                        .description("智聊系统统一 API 文档 —— 涵盖认证、消息、知识图谱、游戏、多模态、管理等模块")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("开发团队")
                                .email("dev@example.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
