-- Phase 14: log of waitlist-confirmation predictions shown to passengers, and their realised outcome after chart.
-- Purpose: measure the model on real traffic (calibration, Brier skill) and retrain from point-in-time features.
-- No PII: features are pool-level numbers; booking_id links to the booking, not to a person's contact data.
CREATE TABLE reservation.wl_prediction (
  id            BIGSERIAL        PRIMARY KEY,
  model_version TEXT             NOT NULL,
  predicted_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
  -- 6-hour bucket: a passenger refreshing their PNR every minute produces at most 4 rows a day.
  log_bucket    BIGINT           NOT NULL,
  train_run_id  BIGINT           NOT NULL,
  run_date      DATE             NOT NULL,
  booking_id    BIGINT           NOT NULL,
  class_code    VARCHAR(3)       NOT NULL,
  quota_code    VARCHAR(3)       NOT NULL,
  features      JSONB            NOT NULL,
  probability   DOUBLE PRECISION NOT NULL CHECK (probability BETWEEN 0 AND 1),
  -- NULL until resolved. VOID: no outcome exists (passenger cancelled, run cancelled) -- excluded from metrics,
  -- exactly as cancelled WL bookings were excluded from training labels.
  outcome       TEXT             CHECK (outcome IN ('CONFIRMED','NOT_CONFIRMED','VOID')),
  resolved_at   TIMESTAMPTZ,
  UNIQUE (booking_id, run_date, model_version, log_bucket)
);
CREATE INDEX ix_wl_prediction_unresolved ON reservation.wl_prediction (train_run_id) WHERE outcome IS NULL;
CREATE INDEX ix_wl_prediction_resolved ON reservation.wl_prediction (resolved_at) WHERE outcome IN ('CONFIRMED','NOT_CONFIRMED');

-- Least privilege (see V8): the app inserts and resolves, never deletes. Retention is an owner job.
DO $$
BEGIN
  IF '${appRole}' <> current_user THEN
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON reservation.wl_prediction TO %I', '${appRole}');
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE reservation.wl_prediction_id_seq TO %I', '${appRole}');
  END IF;
END $$;
