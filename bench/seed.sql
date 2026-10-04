-- Seeds 1,000,000 users (ids 1000..1000999) for the rank benchmark.
-- Ids start at 1000 so they never clash with hand-made test users 1 and 2.
-- XP is random up to 1e9 so ties are rare and ranks are well-defined.
-- Safe to re-run: it clears the previous seed first.
DELETE FROM users WHERE id BETWEEN 1000 AND 1000999;

INSERT INTO users (id, total_xp)
SELECT g, (random() * 1000000000)::bigint
FROM generate_series(1000, 1000999) g;

ANALYZE users;