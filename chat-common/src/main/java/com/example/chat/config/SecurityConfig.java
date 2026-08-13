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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
// @EnableMethodSecurity 会在 Spring Security 6.x 中改变 AuthorizationFilter 行为，
// 导致 SockJS WebSocket 的 xhr_send POST 请求被拒绝（403）。等 SockJS 升级后再启用。
// @EnableMethodSecurity
public class SecurityConfig {
    private final JwtUtil jwtUtil;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtUtil);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(content -> {})
                .xssProtection(xss -> {})
                .addHeaderWriter((request, response) -> {
                    response.setHeader("Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; "
                        + "connect-src 'self' ws: wss:; font-src 'self'; object-src 'none'");
                })
            )
            .authorizeHttpRequests(auth -> auth
                // 白名单：无需登录即可访问（其余接口一律需登录，防止未授权数据泄露）
                .requestMatchers("/api/v1/auth/**").permitAll()          // 登录/注册
                .requestMatchers("/api/v1/health/**").permitAll()        // 服务健康探测（games 降级时前端维护提示）
                .requestMatchers("/actuator/health/**").permitAll()      // 健康检查（health-check.sh / blackbox 探测）
                .requestMatchers("/actuator/prometheus/**").permitAll()  // Prometheus 指标抓取（仅内网可达）
                .requestMatchers("/api/v1/messages/online-count").permitAll()  // 首页在线人数
                .requestMatchers("/api/v1/monitor/total-usage").permitAll()   // 首页累计使用量
                .requestMatchers("/ws/**").permitAll()                   // WebSocket（在线数/聊天推送）
                .requestMatchers("/internal/**").permitAll()             // 内部API（chat-web 调 chat-core，不经公网 Nginx）
                .requestMatchers("/error").permitAll()                   // 错误页面（async dispatch）
                // 管理接口（自有 X-Admin-Password 密码鉴权）
                .requestMatchers("/api/v1/admin/**").permitAll()
                // LLM 模型管理（写操作自有 X-Admin-Pass 密码鉴权，与监控面板同源）
                .requestMatchers("/api/v1/llm/admin/**").permitAll()
                // SQL 执行器（自有 SQL_EXECUTOR_PASSWORD 密码鉴权 + 登录锁定 + 危险SQL拦截）
                .requestMatchers("/api/v1/sql/**").permitAll()
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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://112.124.106.108:*",     // 生产主服务器（IP:端口 直接访问）
                "http://your-nginx-ip:*",           // 生产环境主服务器
                "http://*.your-domain.com",
                "https://*.your-domain.com"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
