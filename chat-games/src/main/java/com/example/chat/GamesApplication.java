package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 游戏服务独立启动类
 * 只扫描 games 相关的 controller/service + common 公共组件
 * 排除 RabbitMQ 自动配置（游戏服务不需要消息队列）
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
public class GamesApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GamesApplication.class);
        app.setAdditionalProfiles("games");
        app.run(args);
    }
}
