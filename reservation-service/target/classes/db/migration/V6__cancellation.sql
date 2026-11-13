-- One row per cancellation event (user, chart drop, or run cancellation): the refund's paper trail.
CREATE TABLE reservation.cancellation (
  id             UUID          PRIMARY KEY,
  run_date       DATE          NOT NULL,
  booking_id     BIGINT        NOT NULL,
  pnr            CHAR(10)      NOT NULL,
  passenger_seqs SMALLINT[]    NOT NULL CHECK (cardinality(passenger_seqs) BETWEEN 1 AND 6),
  kind           TEXT          NOT NULL CHECK (kind IN ('USER','ABANDONED','CHART','RUN_CANCELLED')),
  total_paid     NUMERIC(12,2) NOT NULL CHECK (total_paid >= 0),
  total_charge   NUMERIC(12,2) NOT NULL CHECK (total_charge >= 0),
  refund_amount  NUMERIC(12,2) NOT NULL CHECK (refund_amount >= 0),
  breakdown      JSONB         NOT NULL,
  requested_by   TEXT          NOT NULL,
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CHECK (refund_amount = total_paid - total_charge)
);
CREATE INDEX ix_cancellation_pnr ON reservation.cancellation (pnr);

-- Chart runs resume after a crash: find charts stuck IN_PROGRESS.
CREATE INDEX ix_chart_in_progress ON reservation.chart (started_at) WHERE status = 'IN_PROGRESS';
