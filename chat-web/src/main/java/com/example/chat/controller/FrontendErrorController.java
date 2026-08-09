package com.example.chat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 接收前端 JS 异常上报，便于定位用户反馈的白屏问题
 */
@RestController
@RequestMapping("/api/v1/frontend-error")
public class FrontendErrorController {

    private static final Logger log = LoggerFactory.getLogger(FrontendErrorController.class);

    @PostMapping
    public ResponseEntity<Map<String, Object>> report(@RequestBody Map<String, Object> payload) {
        try {
            String message = String.valueOf(payload.getOrDefault("message", ""));
            String stack = String.valueOf(payload.getOrDefault("stack", ""));
            String url = String.valueOf(payload.getOrDefault("url", ""));
            String userAgent = String.valueOf(payload.getOrDefault("userAgent", ""));
            log.warn("[前端异常] msg={} url={} ua={} stack={}", message, url, userAgent, stack);
        } catch (Exception ignored) {
            log.debug("前端异常上报处理失败，忽略并继续");
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
