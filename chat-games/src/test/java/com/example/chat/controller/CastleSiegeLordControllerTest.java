package com.example.chat.controller;

import com.example.chat.security.AuthUtils;
import com.example.chat.service.CastleSiegeLordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CastleSiegeLordController 真实行为断言：
 * 排行榜透传、recruitedTroops<=0 跳过加分、已登录 user: 前缀、游客 key 归一化、默认名。
 */
@ExtendWith(MockitoExtension.class)
class CastleSiegeLordControllerTest {

    @Mock
    private CastleSiegeLordService lordService;

    private CastleSiegeLordController controller;

    @BeforeEach
    void setUp() {
        controller = new CastleSiegeLordController(lordService);
    }

    @Test
    void getLeaderboard_returnsRanking() {
        when(lordService.getTopLords(5)).thenReturn(List.of(Map.of("rank", 1, "name", "玩家甲")));

        ResponseEntity<?> resp = controller.getLeaderboard(5);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(1, ((List<?>) body.get("ranking")).size());
    }

    @Test
    void syncLeaderboard_zeroTroops_skipsScoreUpdate() {
        when(lordService.getTopLords(10)).thenReturn(List.of());

        ResponseEntity<?> resp = controller.syncLeaderboard(Map.of("recruitedTroops", 0));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, ((Map<?, ?>) resp.getBody()).get("ok"));
        verify(lordService, never()).addLordScore(any(), any(), anyLong(), any());
    }

    @Test
    void syncLeaderboard_loggedInUser_addsScoreWithUserIdPrefix() {
        when(lordService.getTopLords(10)).thenReturn(List.of());

        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUserIdFromContext).thenReturn(100L);

            ResponseEntity<?> resp = controller.syncLeaderboard(Map.of(
                    "recruitedTroops", 30, "displayName", "玩家甲"));

            assertEquals(200, resp.getStatusCode().value());
            verify(lordService).addLordScore(eq("user:100"), eq("玩家甲"), eq(30L), any());
        }
    }

    @Test
    void syncLeaderboard_guestUser_normalizesGuestKey() {
        when(lordService.getTopLords(10)).thenReturn(List.of());
        when(lordService.normalizeGuestKey("player-1")).thenReturn("guest:player-1");

        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUserIdFromContext).thenReturn(null);

            controller.syncLeaderboard(Map.of(
                    "recruitedTroops", 30, "displayName", "匿名", "playerKey", "player-1"));

            verify(lordService).addLordScore(eq("guest:player-1"), eq("匿名"), eq(30L), any());
        }
    }

    @Test
    void syncLeaderboard_blankDisplayName_defaultsToAnonymousLord() {
        when(lordService.getTopLords(10)).thenReturn(List.of());

        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(AuthUtils::extractUserIdFromContext).thenReturn(200L);

            controller.syncLeaderboard(Map.of("recruitedTroops", 30, "displayName", "  "));

            verify(lordService).addLordScore(eq("user:200"), eq("匿名领主"), eq(30L), any());
        }
    }
}
