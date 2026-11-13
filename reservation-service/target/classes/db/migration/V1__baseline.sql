-- Reservation service baseline.
-- Portable to PostgreSQL 15/16/17: exclusion constraints are declared per partition
-- (see V3 ensure_partitions), because partitioned-parent exclusion constraints need PG 17.
-- IDs come from explicit sequences: identity columns on partitioned tables also need PG 17.

CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE SCHEMA IF NOT EXISTS reservation;

CREATE FUNCTION reservation.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;

-- ---------------------------------------------------------------- run structure
CREATE TABLE reservation.train_run (
  id               BIGSERIAL   PRIMARY KEY,
  train_no         VARCHAR(5)  NOT NULL,
  run_date         DATE        NOT NULL,
  status           TEXT        NOT NULL DEFAULT 'SCHEDULED'
                   CHECK (status IN ('SCHEDULED','CHART_1','CHART_2','RUNNING','COMPLETED','CANCELLED')),
  booking_opens_at TIMESTAMPTZ NOT NULL,
  tatkal_opens_at  TIMESTAMPTZ NOT NULL,
  chart1_due_at    TIMESTAMPTZ NOT NULL,
  chart2_due_at    TIMESTAMPTZ NOT NULL,
  version          INT         NOT NULL DEFAULT 0,
  UNIQUE (train_no, run_date)
);
CREATE INDEX ix_run_chart_due ON reservation.train_run (chart1_due_at) WHERE status = 'SCHEDULED';

CREATE TABLE reservation.run_stop (
  train_run_id BIGINT      NOT NULL REFERENCES reservation.train_run,
  stop_seq     SMALLINT    NOT NULL,
  station_code VARCHAR(5)  NOT NULL,
  sched_arr    TIMESTAMPTZ,
  sched_dep    TIMESTAMPTZ,
  distance_km  INT         NOT NULL,
  is_halt      BOOLEAN     NOT NULL DEFAULT true,
  PRIMARY KEY (train_run_id, stop_seq)
);

CREATE TABLE reservation.run_coach (
  id           BIGSERIAL   PRIMARY KEY,
  train_run_id BIGINT      NOT NULL REFERENCES reservation.train_run,
  coach_label  VARCHAR(4)  NOT NULL,
  coach_type   VARCHAR(8)  NOT NULL,
  class_code   VARCHAR(3)  NOT NULL,
  position     SMALLINT    NOT NULL,
  status       TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','WITHDRAWN')),
  UNIQUE (train_run_id, coach_label)
);
CREATE INDEX ix_run_coach_class ON reservation.run_coach (train_run_id, class_code) WHERE status = 'ACTIVE';

CREATE TABLE reservation.berth_layout (
  coach_type   VARCHAR(8) NOT NULL,
  berth_no     SMALLINT   NOT NULL,
  berth_type   VARCHAR(2) NOT NULL CHECK (berth_type IN ('LB','MB','UB','SL','SU','WS','MS','AS')),
  rac_eligible BOOLEAN    NOT NULL,
  PRIMARY KEY (coach_type, berth_no)
);
