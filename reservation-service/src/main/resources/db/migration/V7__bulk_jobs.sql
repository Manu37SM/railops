-- Resumable bulk work (run cancellation). One job per (run, kind): a redelivered disruption event finds the
-- existing job instead of starting a second one. The cursor advances in the same transaction as each chunk.
CREATE TABLE reservation.bulk_job (
  id              BIGSERIAL   PRIMARY KEY,
  train_run_id    BIGINT      NOT NULL,
  kind            TEXT        NOT NULL CHECK (kind IN ('RUN_CANCELLATION')),
  status          TEXT        NOT NULL CHECK (status IN ('RUNNING','COMPLETED')),
  reason          TEXT        NOT NULL,
  requested_by    TEXT        NOT NULL,
  cursor_run_date DATE,
  cursor_id       BIGINT      NOT NULL DEFAULT 0,
  processed       INT         NOT NULL DEFAULT 0,
  refunds         INT         NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ,
  UNIQUE (train_run_id, kind)
);
