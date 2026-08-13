package com.example.chat.controller;

import com.example.chat.service.CastleSiegeBattlefieldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.verify;

/**
 * CastleSiegeBattlefieldController 真实行为断言：
 * MessageMapping 三个入口正确转发到 service。
 */
@ExtendWith(MockitoExtension.class)
class CastleSiegeBattlefieldControllerTest {

    @Mock
    private CastleSiegeBattlefieldService battlefieldService;

    private CastleSiegeBattlefieldController controller;

    @BeforeEach
    void setUp() {
        controller = new CastleSiegeBattlefieldController(battlefieldService);
    }

    @Test
    void join_forwardsToService() {
        Map<String, Object> payload = Map.of("playerKey", "user:1");

        controller.join("s1", payload);

        verify(battlefieldService).join("s1", payload);
    }

    @Test
    void update_forwardsToService() {
        Map<String, Object> payload = Map.of("playerKey", "user:1", "x", 5);

        controller.update("s1", payload);

        verify(battlefieldService).update("s1", payload);
    }

    @Test
    void leave_forwardsToService() {
        Map<String, Object> payload = Map.of("playerKey", "user:1");

        controller.leave("s1", payload);

        verify(battlefieldService).leave("s1", payload);
    }
}
