package com.example.chat.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CastleSiegeLordService {

    private static final String SCORE_KEY = "game:castlesiege:lords:scores";
    private static final String NAME_KEY = "game:castlesiege:lords:names";
    private static final String CAVALRY_KEY = "game:castlesiege:lords:units:cavalry";
    private static final String INFANTRY_KEY = "game:castlesiege:lords:units:infantry";
    private static final String ARCHER_KEY = "game:castlesiege:lords:units:archer";
    private static final String CATAPULT_KEY = "game:castlesiege:lords:units:catapult";
    private static final String[] RANK_TITLES = {
            "天下共主",
            "雄霸一方",
            "知名领主",
            "威震四野",
            "开疆霸主",
            "百战枭雄",
            "镇城统帅",
            "边境雄主",
            "崭露锋芒",
            "新锐领主"
    };

    private final RedisTemplate<String, String> redisTemplate;

    public CastleSiegeLordService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void addLordScore(String memberKey, String displayName, long scoreDelta, Map<String, Long> recruitedByType) {
        if (memberKey == null || memberKey.isBlank() || scoreDelta <= 0) {
            return;
        }

        redisTemplate.opsForZSet().incrementScore(SCORE_KEY, memberKey, scoreDelta);
        if (displayName != null && !displayName.isBlank()) {
            redisTemplate.opsForHash().put(NAME_KEY, memberKey, displayName);
        }
        incrementUnitStat(CAVALRY_KEY, memberKey, recruitedByType.getOrDefault("cavalry", 0L));
        incrementUnitStat(INFANTRY_KEY, memberKey, recruitedByType.getOrDefault("infantry", 0L));
        incrementUnitStat(ARCHER_KEY, memberKey, recruitedByType.getOrDefault("archer", 0L));
        incrementUnitStat(CATAPULT_KEY, memberKey, recruitedByType.getOrDefault("catapult", 0L));
    }

    public List<Map<String, Object>> getTopLords(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10));
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(SCORE_KEY, 0, 49);

        List<Map<String, Object>> ranking = new ArrayList<>();
        if (tuples == null) {
            return ranking;
        }

        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null) {
                continue;
            }
            String memberKey = tuple.getValue();
            if (!memberKey.startsWith("user:")) {
                continue;
            }
            Object storedName = redisTemplate.opsForHash().get(NAME_KEY, memberKey);
            String displayName = storedName != null && !storedName.toString().isBlank()
                    ? storedName.toString()
                    : fallbackDisplayName(memberKey);

            long score = tuple.getScore() == null ? 0L : Math.round(tuple.getScore());
            ranking.add(Map.of(
                    "rank", rank++,
                    "playerKey", memberKey,
                    "name", displayName,
                    "score", score,
                    "title", resolveLordTitle(rank - 1)
            ));
            if (ranking.size() >= safeLimit) {
                break;
            }
        }
        return ranking;
    }

    public String normalizeGuestKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "guest:anonymous";
        }
        String cleaned = rawKey.trim().replaceAll("[^a-zA-Z0-9:_-]", "");
        return cleaned.isBlank() ? "guest:anonymous" : cleaned;
    }

    private String fallbackDisplayName(String memberKey) {
        if (memberKey.startsWith("user:")) {
            return "玩家#" + memberKey.substring("user:".length());
        }
        if (memberKey.startsWith("guest:")) {
            return "访客领主";
        }
        return memberKey;
    }

    private void incrementUnitStat(String redisKey, String memberKey, long value) {
        if (value <= 0) {
            return;
        }
        Long current = readLong(redisTemplate.opsForHash().get(redisKey, memberKey));
        redisTemplate.opsForHash().put(redisKey, memberKey, String.valueOf(current + value));
    }

    private String resolveLordTitle(int rank) {
        int index = Math.max(1, rank) - 1;
        if (index >= 0 && index < RANK_TITLES.length) {
            return RANK_TITLES[index];
        }
        return "城邦领主";
    }

    private long readLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
