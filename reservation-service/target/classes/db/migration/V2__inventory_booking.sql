CREATE TABLE reservation.segment_inventory (
  train_run_id BIGINT     NOT NULL REFERENCES reservation.train_run,
  class_code   VARCHAR(3) NOT NULL,
  quota_code   VARCHAR(3) NOT NULL CHECK (quota_code IN ('GN','LD','SS','TQ','PT','DF','FT','HP','PQ','RL')),
  seg_idx      SMALLINT   NOT NULL,
  cnf_capacity INT        NOT NULL CHECK (cnf_capacity >= 0),
  cnf_used     INT        NOT NULL DEFAULT 0,
  rac_capacity INT        NOT NULL DEFAULT 0,
  rac_used     INT        NOT NULL DEFAULT 0,
  PRIMARY KEY (train_run_id, class_code, quota_code, seg_idx),
  CHECK (cnf_used BETWEEN 0 AND cnf_capacity),
  CHECK (rac_used BETWEEN 0 AND rac_capacity)
) WITH (fillfactor = 70);

CREATE SEQUENCE reservation.allocation_id_seq;
CREATE SEQUENCE reservation.booking_id_seq;
CREATE SEQUENCE reservation.passenger_id_seq;
CREATE SEQUENCE reservation.waitlist_entry_id_seq;

-- No EXCLUDE here: added per partition by ensure_partitions (V3).
CREATE TABLE reservation.allocation (
  id              BIGINT      NOT NULL DEFAULT nextval('reservation.allocation_id_seq'),
  run_date        DATE        NOT NULL,
  train_run_id    BIGINT      NOT NULL,
  run_coach_id    BIGINT      NOT NULL,
  berth_no        SMALLINT    NOT NULL,
  seg_range       INT4RANGE   NOT NULL CHECK (NOT isempty(seg_range) AND lower_inc(seg_range) AND NOT upper_inc(seg_range)),
  slot_range      INT4RANGE   NOT NULL CHECK (slot_range IN ('[1,3)','[1,2)','[2,3)')),
  class_code      VARCHAR(3)  NOT NULL,
  quota_code      VARCHAR(3)  NOT NULL,
  state           TEXT        NOT NULL CHECK (state IN ('HELD','CONFIRMED')),
  hold_expires_at TIMESTAMPTZ,
  passenger_id    BIGINT      NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (run_date, id),
  CHECK ((state = 'HELD') = (hold_expires_at IS NOT NULL))
) PARTITION BY RANGE (run_date);
CREATE INDEX ix_alloc_passenger ON reservation.allocation (passenger_id);
CREATE INDEX ix_alloc_pool      ON reservation.allocation (train_run_id, class_code, quota_code);

CREATE TABLE reservation.allocation_history (
  id              BIGINT      NOT NULL,
  run_date        DATE        NOT NULL,
  train_run_id    BIGINT      NOT NULL,
  run_coach_id    BIGINT      NOT NULL,
  berth_no        SMALLINT    NOT NULL,
  seg_range       INT4RANGE   NOT NULL,
  slot_range      INT4RANGE   NOT NULL,
  class_code      VARCHAR(3)  NOT NULL,
  quota_code      VARCHAR(3)  NOT NULL,
  state           TEXT        NOT NULL,
  hold_expires_at TIMESTAMPTZ,
  passenger_id    BIGINT      NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  released_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  release_reason  TEXT        NOT NULL CHECK (release_reason IN
                  ('CANCELLED','HOLD_EXPIRED','PROMOTED_UP','COACH_WITHDRAWN','RUN_CANCELLED'))
) PARTITION BY RANGE (run_date);

CREATE TABLE reservation.booking (
  id                 BIGINT        NOT NULL DEFAULT nextval('reservation.booking_id_seq'),
  run_date           DATE          NOT NULL,
  pnr                CHAR(10)      NOT NULL,
  train_run_id       BIGINT        NOT NULL,
  user_id            UUID          NOT NULL,
  from_seq           SMALLINT      NOT NULL,
  to_seq             SMALLINT      NOT NULL,
  class_code         VARCHAR(3)    NOT NULL,
  quota_code         VARCHAR(3)    NOT NULL,
  status             TEXT          NOT NULL CHECK (status IN
                     ('PENDING_PAYMENT','CONFIRMED','PARTIALLY_CANCELLED','CANCELLED',
                      'EXPIRED','FAILED_REFUNDING','CHART_CANCELLED','RUN_CANCELLED')),
  total_fare         NUMERIC(12,2) NOT NULL CHECK (total_fare >= 0),
  fare_breakup       JSONB         NOT NULL,
  contact_phone_enc  BYTEA         NOT NULL,
  contact_email_enc  BYTEA,
  contact_phone_hmac BYTEA         NOT NULL,
  payment_id         UUID,
  hold_expires_at    TIMESTAMPTZ,
  created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
  version            INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (run_date, id),
  CHECK (to_seq > from_seq)
) PARTITION BY RANGE (run_date);
CREATE INDEX ix_booking_user    ON reservation.booking (user_id, created_at DESC);
CREATE INDEX ix_booking_pending ON reservation.booking (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_booking_run     ON reservation.booking (train_run_id, status);

CREATE TABLE reservation.pnr_directory (
  pnr        CHAR(10) PRIMARY KEY,
  run_date   DATE     NOT NULL,
  booking_id BIGINT   NOT NULL
);

CREATE TABLE reservation.passenger (
  id                  BIGINT      NOT NULL DEFAULT nextval('reservation.passenger_id_seq'),
  run_date            DATE        NOT NULL,
  booking_id          BIGINT      NOT NULL,
  seq_no              SMALLINT    NOT NULL CHECK (seq_no BETWEEN 1 AND 6),
  name                TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  age                 SMALLINT    NOT NULL CHECK (age BETWEEN 0 AND 125),
  gender              CHAR(1)     NOT NULL CHECK (gender IN ('M','F','T')),
  berth_pref          VARCHAR(2),
  berth_required      BOOLEAN     NOT NULL DEFAULT true,
  status              TEXT        NOT NULL CHECK (status IN ('CNF','RAC','WL','CAN','NO_SHOW','CHART_CAN','NOSB')),
  booking_status_text TEXT        NOT NULL,
  current_status_text TEXT        NOT NULL,
  PRIMARY KEY (run_date, id),
  UNIQUE (run_date, booking_id, seq_no)
) PARTITION BY RANGE (run_date);
CREATE INDEX ix_passenger_booking ON reservation.passenger (booking_id);

CREATE TABLE reservation.waitlist_counter (
  train_run_id BIGINT     NOT NULL,
  class_code   VARCHAR(3) NOT NULL,
  quota_code   VARCHAR(3) NOT NULL,
  next_seq     INT        NOT NULL DEFAULT 1,
  wl_cap       INT        NOT NULL,
  PRIMARY KEY (train_run_id, class_code, quota_code),
  CHECK (next_seq - 1 <= wl_cap)
);

-- PENDING added vs. the Phase 3 draft: unpaid WL entries are not promotable (Phase 4 §4).
CREATE TABLE reservation.waitlist_entry (
  id           BIGINT     NOT NULL DEFAULT nextval('reservation.waitlist_entry_id_seq'),
  run_date     DATE       NOT NULL,
  train_run_id BIGINT     NOT NULL,
  class_code   VARCHAR(3) NOT NULL,
  quota_code   VARCHAR(3) NOT NULL,
  wl_seq       INT        NOT NULL,
  passenger_id BIGINT     NOT NULL,
  seg_range    INT4RANGE  NOT NULL,
  status       TEXT       NOT NULL CHECK (status IN
               ('PENDING','WAITING','PROMOTED_RAC','PROMOTED_CNF','CANCELLED','CHART_EXPIRED')),
  PRIMARY KEY (run_date, id),
  UNIQUE (run_date, train_run_id, class_code, quota_code, wl_seq)
) PARTITION BY RANGE (run_date);
CREATE INDEX ix_wl_promotable ON reservation.waitlist_entry (train_run_id, class_code, quota_code, wl_seq)
  WHERE status = 'WAITING';
CREATE INDEX ix_wl_passenger ON reservation.waitlist_entry (passenger_id);
