package com.example.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 服务健康探测：供前端探测各服务可用性。
 * 背景：games 在高峰期可被 stop-games.sh 主动降级停止以释放内存，
 * 前端经 Nginx 反代调用本端点感知 games 状态，展示"系统维护中"友好提示。
 * 设计：服务异常不抛错、不降级 web 自身，统一返回 200 + status 字段。
 */
@Tag(name = "服务健康探测", description = "供前端探测各服务可用性（如 games 降级时展示维护提示）")
@RestController
@RequestMapping("/api/v1/health")
public class ServiceHealthController {

    private static final Logger log = LoggerFactory.getLogger(ServiceHealthController.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Value("${app.games.base-url:http://127.0.0.1:8083}")
    private String gamesBaseUrl;

    @Operation(summary = "games 服务健康", description = "探测 chat-games 是否在线；服务异常时返回 down 而非报错，web 自身不受影响")
    @GetMapping("/games")
    public ResponseEntity<?> gamesHealth() {
        boolean up = probe(gamesBaseUrl + "/actuator/health");
        return ResponseEntity.ok(Map.of("service", "games", "status", up ? "up" : "down"));
    }

    private boolean probe(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
