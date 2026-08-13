package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CastleSiegeLordService 真实行为断言：
 * addLordScore 守卫与增量写入、getTopLords 过滤/钳制/排名标题/名字回退、normalizeGuestKey 清洗。
 */
@ExtendWith(MockitoExtension.class)
class CastleSiegeLordServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private CastleSiegeLordService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        service = new CastleSiegeLordService(redisTemplate);
    }

    // ────────── addLordScore 守卫 ──────────

    @Test
    void addLordScore_nullMemberKey_returnsEarly() {
        service.addLordScore(null, "玩家A", 10, Map.of());
        verifyNoInteractions(zSetOps, hashOps);
    }

    @Test
    void addLordScore_blankMemberKey_returnsEarly() {
        service.addLordScore("  ", "玩家A", 10, Map.of());
        verifyNoInteractions(zSetOps, hashOps);
    }

    @Test
    void addLordScore_zeroDelta_returnsEarly() {
        service.addLordScore("user:1", "玩家A", 0, Map.of());
        verifyNoInteractions(zSetOps, hashOps);
    }

    @Test
    void addLordScore_negativeDelta_returnsEarly() {
        service.addLordScore("user:1", "玩家A", -5, Map.of());
        verifyNoInteractions(zSetOps, hashOps);
    }

    // ────────── addLordScore 正常路径 ──────────

    @Test
    void addLordScore_incrementsScoreStoresNameAndUnits() {
        service.addLordScore("user:1", "玩家甲", 100, Map.of(
                "cavalry", 5L, "infantry", 0L, "archer", 2L, "catapult", 0L));

        verify(zSetOps).incrementScore("game:castlesiege:lords:scores", "user:1", 100.0);
        verify(hashOps).put("game:castlesiege:lords:names", "user:1", "玩家甲");
        verify(hashOps).put("game:castlesiege:lords:units:cavalry", "user:1", "5");
        verify(hashOps).put("game:castlesiege:lords:units:archer", "user:1", "2");
        // 0 值兵种不写
        verify(hashOps, never()).put(eq("game:castlesiege:lords:units:infantry"), any(), any());
        verify(hashOps, never()).put(eq("game:castlesiege:lords:units:catapult"), any(), any());
    }

    @Test
    void addLordScore_blankDisplayName_skipsNameStore() {
        service.addLordScore("user:1", "  ", 10, Map.of());

        verify(zSetOps).incrementScore(anyString(), anyString(), eq(10.0));
        verify(hashOps, never()).put(eq("game:castlesiege:lords:names"), any(), any());
    }

    @Test
    void addLordScore_existingUnitValue_incrementsOnTop() {
        when(hashOps.get("game:castlesiege:lords:units:cavalry", "user:1")).thenReturn("3");

        service.addLordScore("user:1", "玩家甲", 10,
                Map.of("cavalry", 5L, "infantry", 0L, "archer", 0L, "catapult", 0L));

        verify(hashOps).put("game:castlesiege:lords:units:cavalry", "user:1", "8");
    }

    // ────────── getTopLords ──────────

    @Test
    void getTopLords_filtersNonUserAndBuildsRanking() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                new DefaultTypedTuple<>("guest:abc", 999.0),  // 非 user: 被过滤
                new DefaultTypedTuple<>("user:1", 100.0),
                new DefaultTypedTuple<>("user:2", 60.0)));
        when(zSetOps.reverseRangeWithScores("game:castlesiege:lords:scores", 0, 49))
                .thenReturn(tuples);
        when(hashOps.multiGet(eq("game:castlesiege:lords:names"), anyList()))
                .thenReturn(Arrays.asList("玩家甲", null));

        List<Map<String, Object>> ranking = service.getTopLords(10);

        assertEquals(2, ranking.size());
        Map<String, Object> first = ranking.get(0);
        assertEquals("user:1", first.get("playerKey"));
        assertEquals("玩家甲", first.get("name"));
        assertEquals(1, first.get("rank"));
        assertEquals(100L, first.get("score"));
        assertEquals("天下共主", first.get("title"));
        // 名字缺失回退 玩家#id
        assertEquals("玩家#2", ranking.get(1).get("name"));
        assertEquals("雄霸一方", ranking.get(1).get("title"));
    }

    @Test
    void getTopLords_clampsLimitToRange() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (int i = 1; i <= 15; i++) {
            tuples.add(new DefaultTypedTuple<>("user:" + i, (double) i));
        }
        when(zSetOps.reverseRangeWithScores(anyString(), eq(0L), eq(49L))).thenReturn(tuples);
        when(hashOps.multiGet(anyString(), anyList())).thenReturn(List.of());

        // limit>10 → 最多 10；limit<=0 → 至少 1
        assertEquals(10, service.getTopLords(999).size());
        assertEquals(1, service.getTopLords(0).size());
        assertEquals(1, service.getTopLords(-5).size());
    }

    @Test
    void getTopLords_noTuples_returnsEmpty() {
        when(zSetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertTrue(service.getTopLords(10).isEmpty());
    }

    @Test
    void getTopLords_limitClampedTo10_titlesMapThroughRank10() {
        // limit 钳制 ≤10，排名最多到 10，"城邦领主"回退分支经公开 API 不可达
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (int i = 1; i <= 12; i++) {
            tuples.add(new DefaultTypedTuple<>("user:" + i, (double) (12 - i)));
        }
        when(zSetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(tuples);
        when(hashOps.multiGet(anyString(), anyList())).thenReturn(List.of());

        List<Map<String, Object>> ranking = service.getTopLords(12);

        assertEquals(10, ranking.size());
        assertEquals("天下共主", ranking.get(0).get("title"));
        assertEquals("新锐领主", ranking.get(9).get("title"));
    }

    // ────────── normalizeGuestKey ──────────

    @Test
    void normalizeGuestKey_nullOrBlank_returnsAnonymous() {
        assertEquals("guest:anonymous", service.normalizeGuestKey(null));
        assertEquals("guest:anonymous", service.normalizeGuestKey("   "));
    }

    @Test
    void normalizeGuestKey_cleansSpecialChars() {
        assertEquals("player-123_abc", service.normalizeGuestKey("player-123_abc"));
        assertEquals("guest:anonymous", service.normalizeGuestKey("!!!@@@###"));
        assertEquals("a1-b2:c3", service.normalizeGuestKey("  a1-b2:c3  "));
    }
}
