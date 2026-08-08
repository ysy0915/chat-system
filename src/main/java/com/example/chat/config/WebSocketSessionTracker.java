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

import java.util.*;
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
    private static final String KNOWN_PAGES_KEY = "ws:known:pages";
    private static final String SESSION_HEARTBEAT_PREFIX = "ws:heartbeat:";
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 分钟无操作清理

    /** 随机在线人数上限（0-300） */
    private static final int RANDOM_TOTAL_MAX = 301;

    private final ConcurrentHashMap<String, String> localSessions = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountRedisService onlineCountRedisService;
    private final BroadcastService broadcastService;

    /** 全局虚拟在线总数（随机生成，定时刷新） */
    private final AtomicInteger virtualTotal = new AtomicInteger(0);
    /** 各页面的虚拟在线数（key=page, value=分配后的人数） */
    private final ConcurrentHashMap<String, AtomicInteger> virtualPageCounts = new ConcurrentHashMap<>();
    /** 各页面的真实连接数（用于按比例分配） */
    private final ConcurrentHashMap<String, AtomicInteger> realPageCounts = new ConcurrentHashMap<>();

    public WebSocketSessionTracker(StringRedisTemplate redisTemplate,
                                   OnlineCountRedisService onlineCountRedisService,
                                   BroadcastService broadcastService) {
        this.redisTemplate = redisTemplate;
        this.onlineCountRedisService = onlineCountRedisService;
        this.broadcastService = broadcastService;
        for (String page : DEFAULT_PAGES) {
            redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, page);
            virtualPageCounts.put(page, new AtomicInteger(0));
            realPageCounts.put(page, new AtomicInteger(0));
        }
        // 不在构造器中生成随机数，初始化为真实值（0），
        // 等 60 秒后第一次定时任务再生成虚拟随机数
    }

    /**
     * 每 60 秒刷新一次虚拟在线数
     * 生成 0-300 的随机总数，按各页面真实连接数比例分配
     */
    @Scheduled(fixedRate = 60000)
    public void refreshVirtualCounts() {
        // 1. 收集各页面真实连接数
        Map<String, Integer> realCounts = new LinkedHashMap<>();
        Set<String> pages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (pages == null) pages = DEFAULT_PAGES;

        int realTotal = 0;
        for (String page : pages) {
            int cnt = getRawCount(page);
            realCounts.put(page, cnt);
            realTotal += cnt;
        }

        // 2. 生成随机总数（0-300）
        int newTotal = new Random().nextInt(RANDOM_TOTAL_MAX);

        // 3. 按真实连接数比例分配
        if (realTotal > 0) {
            int allocated = 0;
            List<String> pageList = new ArrayList<>(realCounts.keySet());
            for (int i = 0; i < pageList.size(); i++) {
                String page = pageList.get(i);
                int realCnt = realCounts.get(page);
                if (i == pageList.size() - 1) {
                    // 最后一个页面分配剩余，保证总和精确
                    int v = newTotal - allocated;
                    setVirtualCount(page, v);
                } else {
                    int v = (int) ((long) newTotal * realCnt / realTotal);
                    allocated += v;
                    setVirtualCount(page, v);
                }
            }
        } else {
            // 没有真实连接时，随机分配到几个热门页面
            String[] hotPages = {"chat", "personal", "debate", "games", "landing"};
            int allocated = 0;
            for (int i = 0; i < hotPages.length; i++) {
                if (i == hotPages.length - 1) {
                    setVirtualCount(hotPages[i], newTotal - allocated);
                } else {
                    int v = newTotal / hotPages.length;
                    allocated += v;
                    setVirtualCount(hotPages[i], v);
                }
            }
            // 其他页面置 0
            for (String page : pages) {
                if (!Arrays.asList(hotPages).contains(page)) {
                    setVirtualCount(page, 0);
                }
            }
        }

        virtualTotal.set(newTotal);
        log.info("[OnlineCount] 刷新虚拟在线数 total={} pages={}", newTotal, virtualPageCounts);

        // 4. 广播新的在线数
        broadcastAll();
        for (String page : pages) {
            broadcastPage(page);
        }
    }

    private void setVirtualCount(String page, int count) {
        virtualPageCounts.computeIfAbsent(page, k -> new AtomicInteger(0)).set(Math.max(0, count));
    }

    /** 记录 session 心跳时间（在 register 和心跳时调用） */
    public void touchSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        redisTemplate.opsForValue().set(SESSION_HEARTBEAT_PREFIX + sessionId,
                String.valueOf(System.currentTimeMillis()),
                10, java.util.concurrent.TimeUnit.MINUTES);
    }

    /** 定时清理超时 session（由 @Scheduled 调用） */
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
                    // 超时未活动，清理
                    unregisterUser(sid, page);
                    redisTemplate.delete(SESSION_HEARTBEAT_PREFIX + sid);
                }
            }
        }
    }

    public void registerUser(String sessionId, String userId, String name, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = normalizePage(page);

        redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, pageKey);
        virtualPageCounts.computeIfAbsent(pageKey, k -> new AtomicInteger(0));

        // 清理该 sessionId 在所有 page 的残留（防止 unregister 消息丢失导致只增不减）
        String previousPage = localSessions.get(sessionId);
        if (previousPage != null && !previousPage.equals(pageKey)) {
            removeSessionFromPage(sessionId, previousPage);
        }
        // 兜底：扫描所有已知 page，移除可能残留的 sessionId
        Set<String> knownPages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (knownPages != null) {
            for (String p : knownPages) {
                if (!p.equals(pageKey)) {
                    redisTemplate.opsForSet().remove(SESSION_PAGE_PREFIX + p, sessionId);
                }
            }
        }

        localSessions.put(sessionId, pageKey);
        redisTemplate.opsForSet().add(SESSION_PAGE_PREFIX + pageKey, sessionId);
        touchSession(sessionId); // 更新心跳时间

        boolean isNewVisit = (previousPage == null || !previousPage.equals(pageKey));
        if (isNewVisit) {
            onlineCountRedisService.incrementVisitCount(pageKey, java.time.LocalDateTime.now());
        }

        // 不在每次连接时广播，等定时任务统一刷新（避免频繁广播）
    }

    public void unregisterUser(String sessionId, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = (page != null && !page.isBlank()) ? normalizePage(page) : localSessions.get(sessionId);
        localSessions.remove(sessionId);
        redisTemplate.delete(SESSION_HEARTBEAT_PREFIX + sessionId);
        if (pageKey != null) {
            removeSessionFromPage(sessionId, pageKey);
        }
        // 不在断开时广播，等定时任务统一刷新
    }

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

    /** 获取页面的虚拟在线数（对外展示用） */
    public int getCount(String page) {
        String pageKey = normalizePage(page);
        AtomicInteger v = virtualPageCounts.get(pageKey);
        return v != null ? v.get() : 0;
    }

    /** 获取全局虚拟在线总数 */
    public int getTotalCount() {
        return virtualTotal.get();
    }

    /** 获取各页面虚拟在线数（保证总和 = getTotalCount()） */
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

    private void removeSessionFromPage(String sessionId, String pageKey) {
        redisTemplate.opsForSet().remove(SESSION_PAGE_PREFIX + pageKey, sessionId);
    }

    private void broadcastPage(String pageKey) {
        int count = getCount(pageKey);
        broadcastService.broadcast("/topic/online-count/" + pageKey,
                Map.of("count", count, "page", pageKey));
    }

    private void broadcastAll() {
        broadcastService.broadcast("/topic/online-count/all",
                Map.of(
                        "total", getTotalCount(),           // 虚拟总数（首页展示用）
                        "pages", getAllCounts(),             // 虚拟各页面数（展示用）
                        "realTotal", getRealTotalCount(),    // 真实总数（监控用）
                        "realPages", getAllRealCounts()      // 真实各页面数（监控用）
                ));
    }

    private String normalizePage(String page) {
        return page == null || page.isBlank() ? "global" : page.trim();
    }
}
