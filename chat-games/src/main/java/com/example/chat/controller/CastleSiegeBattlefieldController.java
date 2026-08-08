package com.example.chat.controller;

import com.example.chat.service.CastleSiegeBattlefieldService;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
