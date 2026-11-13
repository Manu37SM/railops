-- Creates monthly partitions and, for allocation, the per-partition no-double-berth constraint.
-- run_date is not in the constraint: a run_coach_id belongs to exactly one run, so two
-- allocations on the same coach are always on the same run_date and therefore the same partition.
CREATE OR REPLACE PROCEDURE reservation.ensure_partitions(months_back INT DEFAULT 1, months_ahead INT DEFAULT 4)
LANGUAGE plpgsql AS $$
DECLARE
  m     DATE;
  t     TEXT;
  part  TEXT;
BEGIN
  FOR i IN -months_back..months_ahead LOOP
    m := (date_trunc('month', current_date) + make_interval(months => i))::date;
    FOREACH t IN ARRAY ARRAY['allocation','allocation_history','booking','passenger','waitlist_entry'] LOOP
      part := t || '_' || to_char(m, 'YYYYMM');
      IF to_regclass('reservation.' || part) IS NULL THEN
        EXECUTE format(
          'CREATE TABLE reservation.%I PARTITION OF reservation.%I FOR VALUES FROM (%L) TO (%L)',
          part, t, m, (m + interval '1 month')::date);
        IF t = 'allocation' THEN
          EXECUTE format(
            'ALTER TABLE reservation.%I ADD CONSTRAINT %I EXCLUDE USING gist '
            '(run_coach_id WITH =, berth_no WITH =, seg_range WITH &&, slot_range WITH &&)',
            part, 'ex_no_double_berth_' || to_char(m, 'YYYYMM'));
          EXECUTE format(
            'CREATE INDEX %I ON reservation.%I (hold_expires_at) WHERE state = ''HELD''',
            'ix_alloc_hold_' || to_char(m, 'YYYYMM'), part);
        END IF;
      END IF;
    END LOOP;
  END LOOP;
END $$;

CALL reservation.ensure_partitions();
