-- Machine identities for service-to-service calls (OAuth2 client_credentials).
-- Secrets are Argon2-hashed like passwords; scopes bound what a service token may do.
CREATE TABLE identity.service_client (
  client_id      TEXT        PRIMARY KEY CHECK (client_id ~ '^[a-z][a-z0-9-]{2,40}$'),
  secret_hash    TEXT        NOT NULL,
  allowed_scopes TEXT[]      NOT NULL,
  enabled        BOOLEAN     NOT NULL DEFAULT true,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
