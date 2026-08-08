package com.example.chat.service;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CastleSiegeBattlefieldService {

    private final ConcurrentHashMap<String, BattlefieldPlayerState> playersBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionByPlayerKey = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final CastleSiegeLordService lordService;
    private final BroadcastService broadcastService;

    public CastleSiegeBattlefieldService(SimpMessagingTemplate messagingTemplate,
                                         CastleSiegeLordService lordService,
                                         BroadcastService broadcastService) {
        this.messagingTemplate = messagingTemplate;
        this.lordService = lordService;
        this.broadcastService = broadcastService;
    }

    public void join(String sessionId, Map<String, Object> payload) {
        upsert(sessionId, payload);
    }

    public void update(String sessionId, Map<String, Object> payload) {
        upsert(sessionId, payload);
    }

    public void leave(String sessionId, Map<String, Object> payload) {
        removePlayer(sessionId, stringValue(payload == null ? null : payload.get("playerKey")), true);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        if (event == null) {
            return;
        }
        removePlayer(event.getSessionId(), null, true);
    }

    private void upsert(String sessionId, Map<String, Object> payload) {
        if (sessionId == null || sessionId.isBlank() || payload == null) {
            return;
        }

        String playerKey = stringValue(payload.get("playerKey"));
        if (playerKey == null || playerKey.isBlank()) {
            return;
        }

        String previousSessionId = sessionByPlayerKey.put(playerKey, sessionId);
        if (previousSessionId != null && !previousSessionId.equals(sessionId)) {
            playersBySession.remove(previousSessionId);
        }

        BattlefieldPlayerState state = new BattlefieldPlayerState(
                sessionId,
                playerKey,
                defaultValue(stringValue(payload.get("displayName")), defaultValue(stringValue(payload.get("name")), "访客玩家")),
                numberValue(payload.get("x")),
                numberValue(payload.get("y")),
                longValue(payload.get("troops")),
                booleanValue(payload.get("alive")),
                defaultValue(stringValue(payload.get("color")), "#f97316"),
                defaultValue(stringValue(payload.get("icon")), "👑"),
                booleanValue(payload.get("eligibleForLeaderboard")),
                longValue(payload.get("recruitedTroops")),
                extractRecruitedByType(payload)
        );

        playersBySession.put(sessionId, state);
        broadcast();
    }

    private void removePlayer(String sessionId, String playerKey, boolean syncLeaderboard) {
        BattlefieldPlayerState removed = null;

        if (sessionId != null && !sessionId.isBlank()) {
            removed = playersBySession.remove(sessionId);
        }

        if (removed == null && playerKey != null && !playerKey.isBlank()) {
            String indexedSessionId = sessionByPlayerKey.remove(playerKey);
            if (indexedSessionId != null) {
                removed = playersBySession.remove(indexedSessionId);
            }
        }

        if (removed == null) {
            return;
        }

        sessionByPlayerKey.remove(removed.playerKey(), removed.sessionId());
        if (syncLeaderboard) {
            syncLordProgress(removed);
        }
        broadcast();
    }

    private void syncLordProgress(BattlefieldPlayerState player) {
        if (player == null || !player.eligibleForLeaderboard() || player.recruitedTroops() <= 0) {
            return;
        }
        if (!player.playerKey().startsWith("user:")) {
            return;
        }

        lordService.addLordScore(
                player.playerKey(),
                player.displayName(),
                player.recruitedTroops(),
                player.recruitedByType()
        );
    }

    private void broadcast() {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (BattlefieldPlayerState player : playersBySession.values()) {
            snapshot.add(Map.of(
                    "playerKey", player.playerKey(),
                    "name", player.displayName(),
                    "x", player.x(),
                    "y", player.y(),
                    "troops", player.troops(),
                    "alive", player.alive(),
                    "color", player.color(),
                    "icon", player.icon()
            ));
        }
        broadcastService.broadcast("/topic/castlesiege.state", Map.of("players", snapshot));
    }

    private Map<String, Long> extractRecruitedByType(Map<String, Object> payload) {
        Object raw = payload.get("recruitedByType");
        if (!(raw instanceof Map<?, ?> stats)) {
            return Map.of(
                    "cavalry", 0L,
                    "infantry", 0L,
                    "archer", 0L,
                    "catapult", 0L
            );
        }

        return Map.of(
                "cavalry", longValue(stats.get("cavalry")),
                "infantry", longValue(stats.get("infantry")),
                "archer", longValue(stats.get("archer")),
                "catapult", longValue(stats.get("catapult"))
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0D : Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private record BattlefieldPlayerState(
            String sessionId,
            String playerKey,
            String displayName,
            double x,
            double y,
            long troops,
            boolean alive,
            String color,
            String icon,
            boolean eligibleForLeaderboard,
            long recruitedTroops,
            Map<String, Long> recruitedByType
    ) {
    }
}
