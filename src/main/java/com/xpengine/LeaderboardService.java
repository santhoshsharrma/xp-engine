package com.xpengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Redis is a derived view. Every method degrades gracefully if Redis is down. */
@Service
public class LeaderboardService {
    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    static final String KEY = "leaderboard:xp";

    private final StringRedisTemplate redis;

    public LeaderboardService(StringRedisTemplate redis) { this.redis = redis; }

    public void setScore(long userId, long totalXp) {
        try {
            redis.opsForZSet().add(KEY, Long.toString(userId), totalXp);
        } catch (Exception e) {
            log.warn("Redis ZADD failed for user {}; rebuild job will repair", userId, e);
        }
    }

    /** 1-based rank, or null if Redis is unavailable or the user is not in the set. */
    public Long rank(long userId) {
        try {
            Long r = redis.opsForZSet().reverseRank(KEY, Long.toString(userId));
            return r == null ? null : r + 1;
        } catch (Exception e) {
            log.warn("Redis ZREVRANK failed for user {}", userId, e);
            return null;
        }
    }

    public Set<TypedTuple<String>> top(int n) {
        return redis.opsForZSet().reverseRangeWithScores(KEY, 0, n - 1);
    }

    public void replaceAll(List<long[]> idAndXp) {
        redis.delete(KEY);
        for (long[] row : idAndXp) {
            redis.opsForZSet().add(KEY, Long.toString(row[0]), row[1]);
        }
    }
}
