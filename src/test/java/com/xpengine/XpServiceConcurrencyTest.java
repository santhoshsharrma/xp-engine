package com.xpengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the Postgres and Redis from docker-compose (docker compose up -d).
 * Uses its own user id (9001) and cleans up after itself.
 */
@SpringBootTest
class XpServiceConcurrencyTest {

    static {
        // Same Windows "Asia/Calcutta" workaround as XpEngineApplication.main (tests don't run main).
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    private static final long USER = 9001L;

    @Autowired XpService xp;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM xp_events WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM users WHERE id = ?", USER);
        Set<String> keys = redis.keys("idem:" + USER + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        redis.opsForZSet().remove(LeaderboardService.KEY, Long.toString(USER));
    }

    @Test
    void sameEventRetriedInParallel_awardsExactlyOnce() throws Exception {
        int n = 50;
        List<EventResponse> results = runParallel(n,
                i -> new EventRequest(USER, "evt-same", EventType.LESSON_COMPLETED));

        long awards = results.stream().filter(r -> !r.duplicate()).count();
        assertEquals(1, awards, "exactly one request may be treated as the real award");
        assertEquals(EventType.LESSON_COMPLETED.xp, totalXp());
        assertEquals(1, eventRows());
    }

    @Test
    void differentEventsInParallel_noLostUpdates() throws Exception {
        int n = 50;
        List<EventResponse> results = runParallel(n,
                i -> new EventRequest(USER, "evt-" + i, EventType.LESSON_COMPLETED));

        assertTrue(results.stream().noneMatch(EventResponse::duplicate));
        assertEquals((long) n * EventType.LESSON_COMPLETED.xp, totalXp());
        assertEquals(n, eventRows());
    }

    @Test
    void redisKeyLost_postgresStillBlocksDuplicate() {
        EventRequest req = new EventRequest(USER, "evt-backstop", EventType.QUIZ_PASSED);

        EventResponse first = xp.award(req);
        assertEquals(false, first.duplicate());

        // Simulate Redis losing the idempotency key (eviction, restart, outage).
        redis.delete("idem:" + USER + ":evt-backstop");

        EventResponse retry = xp.award(req);
        assertEquals(true, retry.duplicate());
        assertEquals(EventType.QUIZ_PASSED.xp, totalXp());
        assertEquals(1, eventRows());
    }
    @Test
    void redisClaimExistsButPostgresEventMissing_recoversAndAwards() {
        EventRequest req =
                new EventRequest(USER, "evt-crash", EventType.LESSON_COMPLETED);

        // Simulate a crash after Redis SET NX succeeded,
        // but before PostgreSQL inserted the event.
        redis.opsForValue().set(
                "idem:" + USER + ":evt-crash",
                "1"
        );

        assertEquals(0, eventRows());
        assertEquals(0L, totalXp());

        // Retry after the simulated crash.
        // Redis says "already claimed", but PostgreSQL has no event row,
        // so XpService should continue and process the event.
        EventResponse recovered = xp.award(req);

        assertEquals(false, recovered.duplicate());
        assertEquals(EventType.LESSON_COMPLETED.xp, recovered.xpAwarded());
        assertEquals(EventType.LESSON_COMPLETED.xp, totalXp());
        assertEquals(1, eventRows());

        // A subsequent retry must now be treated as a duplicate.
        EventResponse duplicate = xp.award(req);

        assertEquals(true, duplicate.duplicate());
        assertEquals(0, duplicate.xpAwarded());
        assertEquals(EventType.LESSON_COMPLETED.xp, totalXp());
        assertEquals(1, eventRows());
    }
    // ---- helpers ----

    private List<EventResponse> runParallel(int n, IntFunction<EventRequest> requestFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<EventResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                EventRequest req = requestFor.apply(i);
                Callable<EventResponse> task = () -> {
                    ready.countDown();
                    go.await();              // release all threads at the same moment
                    return xp.award(req);
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            List<EventResponse> results = new ArrayList<>();
            for (Future<EventResponse> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long totalXp() {
        return jdbc.queryForObject("SELECT total_xp FROM users WHERE id = ?", Long.class, USER);
    }

    private int eventRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM xp_events WHERE user_id = ?", Integer.class, USER);
    }
}
