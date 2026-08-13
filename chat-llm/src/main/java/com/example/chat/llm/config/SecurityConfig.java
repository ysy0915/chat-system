package com.example.chat.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * <h2>安全配置</h2>
 *
 * <p>chat-llm 为内部 AI 服务 (不直接暴露公网)，关闭 Spring Security 默认 Basic Auth，
 * 放行所有请求。鉴权由上游网关 (chat-web / Nginx) 统一处理。</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }
}
