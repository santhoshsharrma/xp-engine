package com.xpengine;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class XpService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final LeaderboardService leaderboard;

    public XpService(JdbcTemplate jdbc, TransactionTemplate tx,
                     StringRedisTemplate redis, LeaderboardService leaderboard) {
        this.jdbc = jdbc; this.tx = tx; this.redis = redis; this.leaderboard = leaderboard;
    }

    public EventResponse award(EventRequest req) {
        String idemKey = "idem:" + req.userId() + ":" + req.eventId();

        // 1. Fast-path claim: ONE atomic SET NX, scoped per user. No check-then-set race.
        Boolean claimed = tryClaim(idemKey);
        if (Boolean.FALSE.equals(claimed)) {
            // Redis says "seen", but a crash may have left the key without a saved event.
            if (eventExists(req)) {
                return duplicate(req.userId());
            }
            // No row: the earlier attempt died after claiming. Fall through and process it.
            // UNIQUE(user_id, event_id) in persist() still stops a concurrent double-award.
        }

        // 2. Postgres commit FIRST. UNIQUE(user_id, event_id) is the real backstop.
        Result result;
        try {
            result = tx.execute(status -> persist(req));
        } catch (RuntimeException e) {
            release(idemKey);   // let the client's retry through
            throw e;
        }
        if (result.duplicate) return duplicate(req.userId());

        // 3. Redis AFTER the commit. Failure here never fails the request.
        leaderboard.setScore(req.userId(), result.totalXp);
        return new EventResponse(false, result.xp, result.totalXp, result.streak,
                leaderboard.rank(req.userId()));
    }

    private Result persist(EventRequest req) {
        int xp = req.type().xp;

        Object[] user;
        try {
            // Row lock serialises concurrent events for the same user.
            user = jdbc.queryForObject(
                "SELECT total_xp, streak, last_active_date, tz FROM users WHERE id = ? FOR UPDATE",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getInt(2), rs.getDate(3), rs.getString(4)},
                req.userId());
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Unknown user " + req.userId());
        }

        int inserted = jdbc.update(
            "INSERT INTO xp_events (user_id, event_id, event_type, xp) VALUES (?,?,?,?) " +
            "ON CONFLICT (user_id, event_id) DO NOTHING",
            req.userId(), req.eventId(), req.type().name(), xp);
        if (inserted == 0) return Result.dup();

        long totalXp = (Long) user[0] + xp;
        LocalDate last = user[2] == null ? null : ((Date) user[2]).toLocalDate();
        LocalDate today = LocalDate.now(ZoneId.of((String) user[3]));
        int streak = StreakCalculator.next(last, (Integer) user[1], today);

        jdbc.update("UPDATE users SET total_xp = ?, streak = ?, last_active_date = ? WHERE id = ?",
                totalXp, streak, Date.valueOf(today), req.userId());
        return new Result(false, xp, totalXp, streak);
    }

    private EventResponse duplicate(long userId) {
        // A retry looks like a success: 200, duplicate=true, nothing awarded.
        Long total = jdbc.queryForObject("SELECT total_xp FROM users WHERE id = ?", Long.class, userId);
        Integer streak = jdbc.queryForObject("SELECT streak FROM users WHERE id = ?", Integer.class, userId);
        return new EventResponse(true, 0, total == null ? 0 : total, streak == null ? 0 : streak,
                leaderboard.rank(userId));
    }

    /** TRUE = we own it, FALSE = already claimed, null/exception = Redis unavailable (fall through to Postgres). */
    private Boolean tryClaim(String key) {
        try {
            return redis.opsForValue().setIfAbsent(key, "1", Duration.ofHours(24));
        } catch (Exception e) {
            return Boolean.TRUE;   // Postgres unique constraint still guarantees correctness
        }
    }

    private boolean eventExists(EventRequest req) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM xp_events WHERE user_id = ? AND event_id = ?",
                Integer.class,
                req.userId(),
                req.eventId()
        );

        return n != null && n > 0;
    }

    private void release(String key) {
        try { redis.delete(key); } catch (Exception ignored) {}
    }

    private record Result(boolean duplicate, int xp, long totalXp, int streak) {
        static Result dup() { return new Result(true, 0, 0, 0); }
    }
}
