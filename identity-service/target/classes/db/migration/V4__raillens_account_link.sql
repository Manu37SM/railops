-- Phase 4C: RailLens accounts moved into this login. One row per moved account; makes the move safe to repeat.
-- (Runtime-role grants come from the default privileges set in V3.)
CREATE TABLE identity.raillens_link (
  raillens_user_id BIGINT      PRIMARY KEY,
  user_id          UUID        NOT NULL UNIQUE REFERENCES identity.app_user (id),
  moved_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
