package com.xpengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rank-lookup benchmark: Postgres (indexed COUNT) vs Redis (ZREVRANK).
 *
 * Skips itself unless at least 100k users are seeded (see bench/seed.sql), so a normal
 * test run is unaffected. Requires docker compose up -d. Stop the app first (port/CPU noise).
 */
@SpringBootTest(properties = {"xp.rebuild.interval=PT24H", "xp.rebuild.initial-delay=PT24H"})
class RankBenchmarkTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    private static final int SAMPLES = 2000;
    private static final int WARMUP = 300;
    private static final int TOP_RUNS = 500;

    private static final String PG_RANK =
            "SELECT COUNT(*) + 1 FROM users WHERE total_xp > (SELECT total_xp FROM users WHERE id = ?)";
    private static final String PG_TOP10 =
            "SELECT id FROM users ORDER BY total_xp DESC LIMIT 10";

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired LeaderboardService leaderboard;
    @Autowired RebuildService rebuild;

    @Test
    void rankLookup_redisVsPostgres() {
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        assumeTrue(users != null && users >= 100_000,
                "Seed users first (bench/seed.sql); skipping benchmark");

        Long inRedis = redis.opsForZSet().zCard(LeaderboardService.KEY);
        if (inRedis == null || !inRedis.equals(users)) {
            long t0 = System.nanoTime();
            rebuild.rebuild();
            System.out.printf("Rebuilt Redis leaderboard from Postgres: %,d users in %.1f s%n",
                    users, (System.nanoTime() - t0) / 1e9);
        }

        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM users ORDER BY random() LIMIT ?", Long.class, SAMPLES);

        // Warm-up: JIT, connection pools, Postgres cache. Not measured.
        for (int i = 0; i < WARMUP; i++) {
            long id = ids.get(i % ids.size());
            jdbc.queryForObject(PG_RANK, Long.class, id);
            leaderboard.rank(id);
        }

        long[] pg = new long[ids.size()];
        long[] rd = new long[ids.size()];
        int mismatches = 0;

        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);

            long t0 = System.nanoTime();
            Long pgRank = jdbc.queryForObject(PG_RANK, Long.class, id);
            pg[i] = System.nanoTime() - t0;

            t0 = System.nanoTime();
            Long redisRank = leaderboard.rank(id);
            rd[i] = System.nanoTime() - t0;

            assertNotNull(redisRank, "Redis returned no rank for user " + id + " (is Redis up and rebuilt?)");
            if (!Objects.equals(pgRank, redisRank)) mismatches++;
        }

        long[] pgTop = new long[TOP_RUNS];
        long[] rdTop = new long[TOP_RUNS];
        for (int i = 0; i < TOP_RUNS; i++) {
            long t0 = System.nanoTime();
            jdbc.queryForList(PG_TOP10, Long.class);
            pgTop[i] = System.nanoTime() - t0;

            t0 = System.nanoTime();
            leaderboard.top(10);
            rdTop[i] = System.nanoTime() - t0;
        }

        System.out.println();
        System.out.println("================ RANK BENCHMARK ================");
        System.out.printf("Users: %,d | rank samples: %,d | top-10 runs: %,d%n", users, ids.size(), TOP_RUNS);
        System.out.printf("Machine: %s, %d cores, Java %s%n",
                System.getProperty("os.name"), Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.version"));
        System.out.println("(Postgres and Redis run in Docker on this same machine; fill in CPU/RAM by hand.)");
        System.out.println();
        report("Rank lookup  - Postgres (indexed COUNT)", pg);
        report("Rank lookup  - Redis    (ZREVRANK)    ", rd);
        System.out.printf("Rank speed-up at p50: %.1fx | at p99: %.1fx%n",
                pct(pg, 0.50) / pct(rd, 0.50), pct(pg, 0.99) / pct(rd, 0.99));
        System.out.println();
        report("Top-10       - Postgres (ORDER BY LIMIT)", pgTop);
        report("Top-10       - Redis    (ZREVRANGE)     ", rdTop);
        System.out.printf("Rank agreement: %d of %d samples differ (ties are the only legitimate cause)%n",
                mismatches, ids.size());
        System.out.println("================================================");

        assertTrue(mismatches <= ids.size() / 100,
                "Postgres and Redis disagree on rank for " + mismatches + " samples");
    }

    private static void report(String label, long[] nanos) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1e6;
        System.out.printf("%s  p50 %8.3f ms | p99 %8.3f ms | mean %8.3f ms%n",
                label, pct(s, 0.50) / 1e6, pct(s, 0.99) / 1e6, mean);
    }

    private static double pct(long[] nanos, double p) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) (s.length * p))];
    }
}
