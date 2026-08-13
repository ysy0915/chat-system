package com.example.chat.controller;

import com.example.chat.service.CastleSiegeBattlefieldService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "城堡攻防-战场", description = "城堡攻防战场 WebSocket 通信")
@RestController
public class CastleSiegeBattlefieldController {

    private final CastleSiegeBattlefieldService battlefieldService;

    public CastleSiegeBattlefieldController(CastleSiegeBattlefieldService battlefieldService) {
        this.battlefieldService = battlefieldService;
    }

    @MessageMapping("/castlesiege.join")
    public void join(@Header("simpSessionId") String sessionId, Map<String, Object> payload) {
        battlefieldService.join(sessionId, payload);
    }

    @MessageMapping("/castlesiege.update")
    public void update(@Header("simpSessionId") String sessionId, Map<String, Object> payload) {
        battlefieldService.update(sessionId, payload);
    }

    @MessageMapping("/castlesiege.leave")
    public void leave(@Header("simpSessionId") String sessionId, Map<String, Object> payload) {
        battlefieldService.leave(sessionId, payload);
    }
}
