package com.example.chat.config;

import com.example.chat.service.BroadcastService;
import com.example.chat.service.OnlineCountRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebSocketSessionTracker {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionTracker.class);

    private static final Set<String> DEFAULT_PAGES = Set.of(
            "landing", "chat", "personal", "debate", "games", "pingpong", "snakeking",
            "castlesiege", "history", "graph", "about", "profile", "admin-models",
            "sql", "monitor", "media", "global"
    );

    private static final String SESSION_PAGE_PREFIX = "ws:page:";
    private static final String SESSION_PAGE_MAP_KEY = "ws:session:page";
    private static final String KNOWN_PAGES_KEY = "ws:known:pages";
    private static final String SESSION_HEARTBEAT_PREFIX = "ws:heartbeat:";
    private static final long IDLE_TIMEOUT_MS = 15 * 60 * 1000; // 15 分钟无操作清理
    private static final int SESSION_PAGE_MAP_TTL_MINUTES = 30;

    private final StringRedisTemplate redisTemplate;
    private final OnlineCountRedisService onlineCountRedisService;
    private final BroadcastService broadcastService;

    /** 全局在线总数（真实连接数，定时刷新） */
    private final AtomicInteger virtualTotal = new AtomicInteger(0);
    /** 各页面的在线数（key=page, value=真实连接数） */
    private final ConcurrentHashMap<String, AtomicInteger> virtualPageCounts = new ConcurrentHashMap<>();

    public WebSocketSessionTracker(StringRedisTemplate redisTemplate,
                                   OnlineCountRedisService onlineCountRedisService,
                                   BroadcastService broadcastService) {
        this.redisTemplate = redisTemplate;
        this.onlineCountRedisService = onlineCountRedisService;
        this.broadcastService = broadcastService;
        for (String page : DEFAULT_PAGES) {
            redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, page);
            virtualPageCounts.put(page, new AtomicInteger(0));
        }
        // 初始化为 0，等 60 秒后第一次定时任务统计真实连接数并刷新
    }

    /**
     * 每 60 秒刷新一次在线数。
     * 直接统计各页面真实 WebSocket 连接数并广播（不做随机放大，如实展示）。
     */
    @Scheduled(fixedRate = 60000)
    public void refreshVirtualCounts() {
        // 1. 收集各页面真实连接数
        Set<String> pages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (pages == null) pages = DEFAULT_PAGES;
        Map<String, Integer> realCounts = collectRealCounts(pages);

        // 2. 直接用真实连接数作为展示值（不生成随机数）
        int realTotal = realCounts.values().stream().mapToInt(Integer::intValue).sum();
        virtualTotal.set(realTotal);
        for (String page : pages) {
            setVirtualCount(page, realCounts.getOrDefault(page, 0));
        }
        log.info("[OnlineCount] 刷新真实在线数 total={} pages={}", realTotal, virtualPageCounts);

        // 3. 广播新的在线数
        broadcastAll();
        for (String page : pages) {
            broadcastPage(page);
        }
    }

    /**
     * 收集各页面真实连接数。
     * @param pages 待查询的页面集合
     * @return page -> 真实连接数 映射（保持插入顺序）
     */
    private Map<String, Integer> collectRealCounts(Set<String> pages) {
        Map<String, Integer> realCounts = new LinkedHashMap<>();
        for (String page : pages) {
            realCounts.put(page, getRawCount(page));
        }
        return realCounts;
    }

    /**
     * 设置指定页面的在线数（负值会被截断为 0）。
     * @param page 页面标识
     * @param count 在线数
     */
    private void setVirtualCount(String page, int count) {
        virtualPageCounts.computeIfAbsent(page, k -> new AtomicInteger(0)).set(Math.max(0, count));
    }

    /**
     * 记录 session 心跳时间（在 register 和心跳时调用）。
     * @param sessionId 会话 ID（空值直接返回）
     */
    public void touchSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        redisTemplate.opsForValue().set(SESSION_HEARTBEAT_PREFIX + sessionId,
                String.valueOf(System.currentTimeMillis()),
                10, java.util.concurrent.TimeUnit.MINUTES);
    }

    /**
     * 定时清理超时 session（由 @Scheduled 调用）。
     * 遍历所有已知页面，对超过 {@link #IDLE_TIMEOUT_MS} 未活动的会话执行注销并删除心跳。
     */
    public void cleanupIdleSessions() {
        Set<String> knownPages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (knownPages == null) return;
        long now = System.currentTimeMillis();
        for (String page : knownPages) {
            Set<String> sessionIds = redisTemplate.opsForSet().members(SESSION_PAGE_PREFIX + page);
            if (sessionIds == null) continue;
            for (String sid : sessionIds) {
                String hb = redisTemplate.opsForValue().get(SESSION_HEARTBEAT_PREFIX + sid);
                long lastActive = (hb != null) ? Long.parseLong(hb) : 0;
                if (now - lastActive > IDLE_TIMEOUT_MS) {
                    // 超时未活动，清理（unregisterUser 会自动清除 Redis Hash + 心跳 + 页面集合）
                    unregisterUser(sid, page);
                }
            }
        }
    }

    /**
     * 注册用户会话到指定页面（多实例安全）。
     * <p>流程：
     * <ol>
     *   <li>规范化页面标识，并将其加入已知页面集合</li>
     *   <li>从 Redis 查询该 sessionId 之前所在的页面，清理旧页面残留</li>
     *   <li>将 sessionId→page 映射写入 Redis Hash（支持多实例共享）</li>
     *   <li>写入 Redis 页面集合并刷新心跳</li>
     *   <li>若是新访问，则累加页面访问计数</li>
     * </ol>
     * 注：不在每次连接时广播，等定时任务统一刷新（避免频繁广播）。
     *
     * @param sessionId 会话 ID（空值直接返回）
     * @param userId 用户 ID
     * @param name 用户名称
     * @param page 页面标识（为空时规范化为 "global"）
     */
    public void registerUser(String sessionId, String userId, String name, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = normalizePage(page);

        redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, pageKey);
        virtualPageCounts.computeIfAbsent(pageKey, k -> new AtomicInteger(0));

        // 从 Redis 查询该 session 之前所在的页面（多实例共享，替代本地 localSessions）
        String previousPage = redisTemplate.opsForHash()
                .get(SESSION_PAGE_MAP_KEY, sessionId) instanceof String s ? s : null;

        // 清理该 sessionId 在其他页面的残留
        if (previousPage != null && !previousPage.equals(pageKey)) {
            removeSessionFromPage(sessionId, previousPage);
        }
        // 兜底：扫描所有已知 page，移除该 sessionId 的残留（覆盖边缘情况）
        Set<String> knownPages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (knownPages != null) {
            for (String p : knownPages) {
                if (!p.equals(pageKey)) {
                    redisTemplate.opsForSet().remove(SESSION_PAGE_PREFIX + p, sessionId);
                }
            }
        }

        // 将 session→page 映射写入 Redis Hash（多实例共享，带 TTL）
        redisTemplate.opsForHash().put(SESSION_PAGE_MAP_KEY, sessionId, pageKey);
        redisTemplate.expire(SESSION_PAGE_MAP_KEY, SESSION_PAGE_MAP_TTL_MINUTES, java.util.concurrent.TimeUnit.MINUTES);

        redisTemplate.opsForSet().add(SESSION_PAGE_PREFIX + pageKey, sessionId);
        touchSession(sessionId); // 更新心跳时间

        boolean isNewVisit = (previousPage == null || !previousPage.equals(pageKey));
        if (isNewVisit) {
            onlineCountRedisService.incrementVisitCount(pageKey, java.time.LocalDateTime.now());
        }

        // 不在每次连接时广播，等定时任务统一刷新（避免频繁广播）
    }

    /**
     * 注销用户会话（多实例安全）。
     * <p>从 Redis Hash / 心跳 / 页面集合中移除该 sessionId。
     * page 为 null 时从 Redis Hash 查询所属页面。
     * 不在断开时广播，等定时任务统一刷新。
     * @param sessionId 会话 ID（空值直接返回）
     * @param page 页面标识（为空时从 Redis Hash 推断）
     */
    public void unregisterUser(String sessionId, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = (page != null && !page.isBlank())
                ? normalizePage(page)
                : redisTemplate.opsForHash().get(SESSION_PAGE_MAP_KEY, sessionId) instanceof String s ? s : null;

        // 从 Redis Hash 中移除 session→page 映射
        redisTemplate.opsForHash().delete(SESSION_PAGE_MAP_KEY, sessionId);
        redisTemplate.delete(SESSION_HEARTBEAT_PREFIX + sessionId);
        if (pageKey != null) {
            removeSessionFromPage(sessionId, pageKey);
        }
        // 不在断开时广播，等定时任务统一刷新
    }

    /**
     * 处理 WebSocket 断开事件：清理对应会话。
     * @param event 断开事件（为 null 时直接返回）
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        if (event == null) return;
        unregisterUser(event.getSessionId(), null);
    }

    /** 获取页面的真实连接数（内部用） */
    private int getRawCount(String page) {
        Long size = redisTemplate.opsForSet().size(SESSION_PAGE_PREFIX + normalizePage(page));
        return size != null ? size.intValue() : 0;
    }

    /** 获取页面的在线数（对外展示用，真实连接数） */
    public int getCount(String page) {
        String pageKey = normalizePage(page);
        AtomicInteger v = virtualPageCounts.get(pageKey);
        return v != null ? v.get() : 0;
    }

    /** 获取全局在线总数（真实连接数） */
    public int getTotalCount() {
        return virtualTotal.get();
    }

    /** 获取各页面在线数（保证总和 = getTotalCount()） */
    public Map<String, Integer> getAllCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> pages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (pages != null) {
            for (String page : pages) {
                AtomicInteger v = virtualPageCounts.get(page);
                result.put(page, v != null ? v.get() : 0);
            }
        }
        return result;
    }

    /** 获取各页面真实在线数（监控页面用） */
    public Map<String, Integer> getAllRealCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> pages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (pages != null) {
            for (String page : pages) {
                result.put(page, getRawCount(page));
            }
        }
        return result;
    }

    /** 获取真实在线总数（监控页面用） */
    public int getRealTotalCount() {
        return getAllRealCounts().values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 从指定页面的会话集合中移除 sessionId。
     */
    private void removeSessionFromPage(String sessionId, String pageKey) {
        redisTemplate.opsForSet().remove(SESSION_PAGE_PREFIX + pageKey, sessionId);
    }

    /**
     * 广播单个页面的在线数到对应 topic。
     */
    private void broadcastPage(String pageKey) {
        int count = getCount(pageKey);
        int hourlyActive = onlineCountRedisService.getHourlyActiveCount();
        broadcastService.broadcast("/topic/online-count/" + pageKey,
                Map.of("count", count, "page", pageKey, "hourlyActive", hourlyActive));
    }

    /**
     * 广播全局在线数（真实总数/各页面数）到总 topic。
     * total/pages 与 realTotal/realPages 现在含义一致，均保留以兼容前端与监控页面。
     */
    private void broadcastAll() {
        broadcastService.broadcast("/topic/online-count/all",
                Map.of(
                        "total", getTotalCount(),           // 总数（首页展示用）
                        "pages", getAllCounts(),             // 各页面数（展示用）
                        "realTotal", getRealTotalCount(),    // 总数（监控用）
                        "realPages", getAllRealCounts()      // 各页面数（监控用）
                ));
    }

    /**
     * 规范化页面标识：null/空白返回 "global"，否则去除首尾空白。
     */
    private String normalizePage(String page) {
        return page == null || page.isBlank() ? "global" : page.trim();
    }
}
