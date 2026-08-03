package com.example.chat.controller;

import com.example.chat.security.JwtUtil;
import com.example.chat.service.CastleSiegeLordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/games/castlesiege/lords")
public class CastleSiegeLordController {

    private final CastleSiegeLordService lordService;
    private final JwtUtil jwtUtil;

    public CastleSiegeLordController(CastleSiegeLordService lordService, JwtUtil jwtUtil) {
        this.lordService = lordService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping
    public ResponseEntity<?> getLeaderboard(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        List<Map<String, Object>> ranking = lordService.getTopLords(limit);
        return ResponseEntity.ok(Map.of("ranking", ranking));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncLeaderboard(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                             @RequestBody(required = false) Map<String, Object> body) {
        long recruitedTroops = toLong(body == null ? null : body.get("recruitedTroops"));
        if (recruitedTroops <= 0) {
            return ResponseEntity.ok(Map.of("ok", true, "ranking", lordService.getTopLords(10)));
        }

        Long userId = extractUserId(authHeader);
        String displayName = extractDisplayName(body);
        String playerKey = userId != null
                ? "user:" + userId
                : lordService.normalizeGuestKey(body == null ? null : stringValue(body.get("playerKey")));

        lordService.addLordScore(playerKey, displayName, recruitedTroops, extractRecruitedByType(body));
        return ResponseEntity.ok(Map.of("ok", true, "ranking", lordService.getTopLords(10)));
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return null;
        }
        return jwtUtil.getUserId(token);
    }

    private String extractDisplayName(Map<String, Object> body) {
        String displayName = stringValue(body == null ? null : body.get("displayName"));
        return (displayName == null || displayName.isBlank()) ? "匿名领主" : displayName.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Map<String, Long> extractRecruitedByType(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        Object raw = body.get("recruitedByType")
                ;
        if (!(raw instanceof Map<?, ?> stats)) {
            return Map.of();
        }
        return Map.of(
                "cavalry", toLong(stats.get("cavalry")),
                "infantry", toLong(stats.get("infantry")),
                "archer", toLong(stats.get("archer")),
                "catapult", toLong(stats.get("catapult"))
        );
    }
}
