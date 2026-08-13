package com.example.chat.llm;

import com.example.chat.config.GlobalExceptionHandler;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.security.JwtUtil;
import com.example.chat.service.DirectLLMClient;
import com.example.chat.util.BaseUrlResolver;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;

/**
 * <h2>chat-llm 启动类</h2>
 *
 * <p>LLM 多模型路由 + LangGraph 引擎，端口 9095，gRPC 9195。</p>
 *
 * <p>{@link EnableAspectJAutoProxy} 开启 AOP 代理以支持
 * Resilience4j 的 {@code @CircuitBreaker} / {@code @Retry} / {@code @RateLimiter}
 * 等注解。</p>
 *
 * <p>chat-common 的 MyBatis Mapper（{@code com.example.chat.repository}）、本模块
 * 遗留 RAG Mapper（{@code com.example.chat.llm.rag.legacy}）与模型管理面
 * Mapper（{@code com.example.chat.llm.llm.routing.db}）均位于默认扫描路径之外，
 * 公共组件（LlmConfigProperties / DirectLLMClient / BaseUrlResolver）也需显式注册。</p>
 */
@SpringBootApplication
@EnableAspectJAutoProxy
@MapperScan({"com.example.chat.repository",
        "com.example.chat.llm.rag.legacy",
        "com.example.chat.llm.llm.routing.db"})
@Import({LlmConfigProperties.class, DirectLLMClient.class, BaseUrlResolver.class, JwtUtil.class,
        GlobalExceptionHandler.class})
public class LlmApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmApplication.class, args);
    }
}
