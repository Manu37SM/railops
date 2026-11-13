CREATE TABLE reservation.idempotency_key (
  user_id       UUID        NOT NULL,
  idem_key      VARCHAR(64) NOT NULL,
  request_hash  BYTEA       NOT NULL,
  status        TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_code SMALLINT,
  response_body JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX ix_idem_gc ON reservation.idempotency_key (created_at);

CREATE TABLE reservation.outbox (
  id             BIGSERIAL   PRIMARY KEY,
  event_id       UUID        NOT NULL UNIQUE,
  aggregate_type TEXT        NOT NULL,
  aggregate_id   TEXT        NOT NULL,
  topic          TEXT        NOT NULL,
  event_type     TEXT        NOT NULL,
  payload        JSONB       NOT NULL,
  headers        JSONB       NOT NULL DEFAULT '{}',
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ,
  attempts       INT         NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON reservation.outbox (id) WHERE published_at IS NULL;

CREATE TABLE reservation.processed_event (
  consumer     TEXT        NOT NULL,
  event_id     UUID        NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE reservation.chart (
  train_run_id BIGINT      NOT NULL REFERENCES reservation.train_run,
  chart_no     SMALLINT    NOT NULL CHECK (chart_no IN (1,2)),
  status       TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS','PREPARED','FAILED')),
  started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  prepared_at  TIMESTAMPTZ,
  PRIMARY KEY (train_run_id, chart_no)
);
