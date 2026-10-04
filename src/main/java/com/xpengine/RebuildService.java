package com.xpengine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** Safety net: rebuilds the Redis leaderboard from Postgres, the source of truth. */
@Service
public class RebuildService {
    private final JdbcTemplate jdbc;
    private final LeaderboardService leaderboard;

    public RebuildService(JdbcTemplate jdbc, LeaderboardService leaderboard) {
        this.jdbc = jdbc; this.leaderboard = leaderboard;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public int rebuild() {
        List<long[]> rows = jdbc.query("SELECT id, total_xp FROM users",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)});
        leaderboard.replaceAll(rows);
        return rows.size();
    }
}
