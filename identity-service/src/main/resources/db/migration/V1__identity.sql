CREATE SCHEMA IF NOT EXISTS identity;

CREATE FUNCTION identity.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;

-- Email is TEXT normalized to lower case by the application (CHECK enforces it), not CITEXT:
-- with JPA, a varchar parameter compared to a citext column can resolve to the case-sensitive
-- text = text operator, silently breaking uniqueness and login lookups.
CREATE TABLE identity.app_user (
  id             UUID        PRIMARY KEY,
  email          TEXT        UNIQUE CHECK (email = lower(email) AND length(email) <= 254),
  phone_hmac     BYTEA       UNIQUE,
  phone_enc      BYTEA,
  password_hash  TEXT        NOT NULL,
  user_type      TEXT        NOT NULL CHECK (user_type IN ('PASSENGER','STAFF','AGENT')),
  status         TEXT        NOT NULL CHECK (status IN ('ACTIVE','PENDING_MFA','DISABLED','ERASED')),
  mfa_secret_enc BYTEA,
  mfa_last_step  BIGINT      NOT NULL DEFAULT 0,      -- TOTP replay protection
  failed_logins  SMALLINT    NOT NULL DEFAULT 0,
  locked_until   TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  version        INT         NOT NULL DEFAULT 0,
  CHECK (email IS NOT NULL OR phone_hmac IS NOT NULL OR status = 'ERASED'),
  -- Staff can only be ACTIVE once MFA is enrolled.
  CHECK (user_type = 'PASSENGER' OR status <> 'ACTIVE' OR mfa_secret_enc IS NOT NULL)
);

CREATE TABLE identity.user_role (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     UUID        NOT NULL REFERENCES identity.app_user,
  role_code   TEXT        NOT NULL CHECK (role_code IN
              ('PASSENGER','AGENT','STATION_MASTER','CONTROLLER','CREW_CONTROLLER','REVENUE_ANALYST','ADMIN','AUDITOR')),
  scope_type  TEXT        NOT NULL CHECK (scope_type IN ('GLOBAL','ZONE','DIVISION','STATION')),
  scope_value TEXT        NOT NULL CHECK ((scope_type = 'GLOBAL') = (scope_value = '*')),
  granted_by  UUID        NOT NULL,
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, role_code, scope_type, scope_value)
);
CREATE INDEX ix_user_role_user ON identity.user_role (user_id);

CREATE TABLE identity.refresh_token (
  id          UUID        PRIMARY KEY,
  user_id     UUID        NOT NULL REFERENCES identity.app_user,
  family_id   UUID        NOT NULL,
  token_hash  BYTEA       NOT NULL UNIQUE,
  replaced_by UUID,
  replaced_at TIMESTAMPTZ,
  revoked_at  TIMESTAMPTZ,
  expires_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_agent  TEXT,
  ip          TEXT
);
CREATE INDEX ix_rt_family ON identity.refresh_token (family_id);
CREATE INDEX ix_rt_user   ON identity.refresh_token (user_id) WHERE revoked_at IS NULL;

CREATE TABLE identity.audit_log (
  id              BIGSERIAL   PRIMARY KEY,
  source_event_id UUID        UNIQUE,                    -- dedupe for events relayed from other services
  actor_id        TEXT        NOT NULL,
  action          TEXT        NOT NULL,
  target_type     TEXT        NOT NULL,
  target_id       TEXT        NOT NULL,
  detail          JSONB       NOT NULL,
  ip              TEXT,
  occurred_at     TIMESTAMPTZ NOT NULL,
  prev_hash       BYTEA       NOT NULL,
  hash            BYTEA       NOT NULL UNIQUE
);
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON identity.audit_log
  FOR EACH ROW EXECUTE FUNCTION identity.forbid_mutation();
