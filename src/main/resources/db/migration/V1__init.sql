CREATE TABLE users (
  id               BIGINT PRIMARY KEY,
  total_xp         BIGINT NOT NULL DEFAULT 0,
  streak           INT    NOT NULL DEFAULT 0,
  last_active_date DATE,
  tz               TEXT   NOT NULL DEFAULT 'Asia/Kolkata'
);
CREATE INDEX idx_users_total_xp ON users (total_xp DESC);

-- Postgres is the source of truth for idempotency; the Redis key is only a fast path.
CREATE TABLE xp_events (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id),
  event_id   TEXT   NOT NULL,
  event_type TEXT   NOT NULL,
  xp         INT    NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, event_id)
);
