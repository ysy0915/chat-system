package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Web 接入层启动类
 * 扫描 chat-web 的 controller + chat-core 的 service + chat-common 的公共组件
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@ComponentScan(basePackages = {"com.example.chat"})
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
