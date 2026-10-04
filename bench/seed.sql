-- Seed 1M users for the rank benchmark. Run after Flyway has created the schema.
INSERT INTO users (id, total_xp)
SELECT g, (random() * 100000)::bigint FROM generate_series(1, 1000000) g;
ANALYZE users;

-- The Postgres side of the benchmark: rank = users ahead of you + 1.
-- EXPLAIN (ANALYZE, BUFFERS)
-- SELECT COUNT(*) + 1 FROM users WHERE total_xp > (SELECT total_xp FROM users WHERE id = 500000);
