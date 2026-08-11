package com.example.chat.llm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * <h2>chat-llm 启动类</h2>
 *
 * <p>LLM 多模型路由 + LangGraph 引擎，端口 9095，gRPC 9195。</p>
 *
 * <p>{@link EnableAspectJAutoProxy} 开启 AOP 代理以支持
 * Resilience4j 的 {@code @CircuitBreaker} / {@code @Retry} / {@code @RateLimiter}
 * 等注解。</p>
 */
@SpringBootApplication
@EnableAspectJAutoProxy
public class LlmApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmApplication.class, args);
    }
}
