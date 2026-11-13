CREATE SCHEMA IF NOT EXISTS notification;

-- What was (or wasn't) sent, per source event. recipient is stored MASKED: this table is for ops and support,
-- and must not become a second copy of passengers' phone numbers.
CREATE TABLE notification.delivery (
  id              BIGSERIAL   PRIMARY KEY,
  source_event_id UUID        NOT NULL,
  pnr             CHAR(10)    NOT NULL,
  channel         TEXT        NOT NULL CHECK (channel IN ('SMS','EMAIL')),
  template        TEXT        NOT NULL,
  recipient_masked TEXT       NOT NULL,
  body            TEXT        NOT NULL,
  status          TEXT        NOT NULL CHECK (status IN ('SENT','SUPPRESSED_RATE_LIMIT','NO_CONTACT')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_event_id, channel)
);
CREATE INDEX ix_delivery_pnr_time ON notification.delivery (pnr, created_at);
