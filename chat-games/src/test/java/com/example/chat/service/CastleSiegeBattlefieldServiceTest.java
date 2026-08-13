package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CastleSiegeBattlefieldService 真实行为断言：
 * join/update/leave 守卫与广播、同玩家换 session 清理、断开清理 + 排行榜同步条件。
 */
@ExtendWith(MockitoExtension.class)
class CastleSiegeBattlefieldServiceTest {

    @Mock
    private CastleSiegeLordService lordService;

    @Mock
    private BroadcastService broadcastService;

    private CastleSiegeBattlefieldService service;

    @BeforeEach
    void setUp() {
        service = new CastleSiegeBattlefieldService(lordService, broadcastService);
    }

    private Map<String, Object> eligiblePlayerPayload(String playerKey) {
        return Map.of(
                "playerKey", playerKey,
                "displayName", "玩家甲",
                "x", 10, "y", 20,
                "troops", 100, "alive", true,
                "eligibleForLeaderboard", true,
                "recruitedTroops", 50,
                "recruitedByType", Map.of("cavalry", 5L));
    }

    @Test
    void join_nullSessionId_returnsEarly() {
        service.join(null, eligiblePlayerPayload("user:1"));
        verifyNoInteractions(broadcastService);
    }

    @Test
    void join_blankSessionId_returnsEarly() {
        service.join("  ", eligiblePlayerPayload("user:1"));
        verifyNoInteractions(broadcastService);
    }

    @Test
    void join_nullPayload_returnsEarly() {
        service.join("s1", null);
        verifyNoInteractions(broadcastService);
    }

    @Test
    void join_missingPlayerKey_returnsEarly() {
        service.join("s1", Map.of("displayName", "玩家甲"));
        verifyNoInteractions(broadcastService);
    }

    @Test
    void join_validPlayer_broadcastsSnapshot() {
        service.join("s1", eligiblePlayerPayload("user:1"));

        verify(broadcastService).broadcast(eq("/topic/castlesiege.state"), any());
    }

    @Test
    void update_samePlayerNewSession_removesPreviousSession() {
        service.join("s1", eligiblePlayerPayload("user:1"));
        service.join("s2", eligiblePlayerPayload("user:1"));

        // 同 playerKey 换 session → 旧 session 移除，仅剩新 session（两次广播）
        verify(broadcastService, times(2)).broadcast(eq("/topic/castlesiege.state"), any());
    }

    @Test
    void leave_existingPlayer_broadcasts() {
        service.join("s1", eligiblePlayerPayload("user:1"));

        service.leave("s1", Map.of("playerKey", "user:1"));

        verify(broadcastService, times(2)).broadcast(eq("/topic/castlesiege.state"), any());
    }

    @Test
    void leave_unknownSession_noBroadcast() {
        service.leave("unknown", Map.of("playerKey", "nobody"));
        verifyNoInteractions(broadcastService);
    }

    @Test
    void handleDisconnect_nullEvent_noop() {
        service.handleDisconnect(null);
        verifyNoInteractions(broadcastService);
    }

    @Test
    void handleDisconnect_joinedUser_removesAndSyncsLeaderboard() {
        service.join("s1", eligiblePlayerPayload("user:1"));

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("s1");
        service.handleDisconnect(event);

        verify(broadcastService, times(2)).broadcast(eq("/topic/castlesiege.state"), any());
        // user: 前缀 + eligible + recruitedTroops>0 → 同步排行榜
        verify(lordService).addLordScore(eq("user:1"), eq("玩家甲"), eq(50L), any());
    }

    @Test
    void handleDisconnect_guestPlayer_notSynced() {
        service.join("s1", eligiblePlayerPayload("guest:abc"));

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("s1");
        service.handleDisconnect(event);

        verify(lordService, never()).addLordScore(any(), any(), anyLong(), any());
    }

    @Test
    void handleDisconnect_notEligible_notSynced() {
        service.join("s1", Map.of(
                "playerKey", "user:1",
                "eligibleForLeaderboard", false,
                "recruitedTroops", 50));

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("s1");
        service.handleDisconnect(event);

        verify(lordService, never()).addLordScore(any(), any(), anyLong(), any());
    }
}
