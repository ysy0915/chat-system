package com.example.chat.config;

import com.example.chat.security.JwtAuthenticationFilter;
import com.example.chat.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {
    private final JwtUtil jwtUtil;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtUtil);

        http
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(content -> {})
                .xssProtection(xss -> {})
            )
            .authorizeHttpRequests(auth -> auth
                // 白名单：无需登录即可访问
                .requestMatchers("/api/v1/auth/**").permitAll()         // 登录/注册
                .requestMatchers("/api/v1/messages/**").permitAll()     // 发消息/历史消息
                .requestMatchers("/api/v1/graph/**").permitAll()        // 知识图谱
                .requestMatchers("/api/v1/games/**").permitAll()        // 游戏（城堡攻防）
                .requestMatchers("/api/v1/debate/**").permitAll()       // 观点辩论
                .requestMatchers("/api/v1/monitor/**").permitAll()      // 监控面板
                .requestMatchers("/ws/**").permitAll()                  // WebSocket
                .requestMatchers("/internal/**").permitAll()            // 内部API（chat-web调用）
                .requestMatchers("/actuator/health").permitAll()        // 健康检查
                // 管理接口（自有 X-Admin-Password 密码鉴权）
                .requestMatchers("/api/v1/admin/**").permitAll()
                // 其余接口需登录
                .anyRequest().authenticated()
            )
            // 未登录返回 JSON 而非重定向登录页
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
