package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 多模态服务独立启动类
 * 只扫描 media 相关的 controller/service + common 公共组件
 * 排除 RabbitMQ 自动配置（多模态服务不需要消息队列）
 */
@SpringBootApplication(exclude = {RabbitAutoConfiguration.class})
@ComponentScan(basePackages = {
    "com.example.chat.common",
    "com.example.chat.config",
    "com.example.chat.security",
    "com.example.chat.entity",
    "com.example.chat.repository",
    "com.example.chat.service",
    "com.example.chat.controller"
})
public class MediaApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MediaApplication.class);
        app.setAdditionalProfiles("media");
        app.run(args);
    }
}
