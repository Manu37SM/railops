-- Reference layouts. Repeatable migration: re-applied when this file changes.
-- LHB 3A: 72 berths, bays of 8: LB MB UB LB MB UB SL SU. Side-lowers are RAC-eligible.
-- LHB SL (ICF-style numbering) uses the same 8-berth bay pattern.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT ct, n,
       (ARRAY['LB','MB','UB','LB','MB','UB','SL','SU'])[((n - 1) % 8) + 1],
       ((n - 1) % 8) + 1 = 7
FROM unnest(ARRAY['LHB_3A','LHB_SL']) AS ct, generate_series(1, 72) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;

-- LHB 2A: 48 berths, bays of 6: LB UB LB UB SL SU.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT 'LHB_2A', n,
       (ARRAY['LB','UB','LB','UB','SL','SU'])[((n - 1) % 6) + 1],
       ((n - 1) % 6) + 1 = 5
FROM generate_series(1, 48) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;

-- Phase 6: the other classes, so whole trains can be opened (layouts follow the common LHB / ICF designs; berth and
-- seat numbers are RailOps' own, not Indian Railways' records).
-- LHB 1A: 24 berths in cabins and coupes, lower/upper alternating. No RAC in 1A.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT 'LHB_1A', n, CASE WHEN n % 2 = 1 THEN 'LB' ELSE 'UB' END, false
FROM generate_series(1, 24) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;

-- LHB 3E (AC 3-tier economy): 83 berths, same 8-berth bay pattern as 3A. Side-lowers are RAC-eligible.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT 'LHB_3E', n,
       (ARRAY['LB','MB','UB','LB','MB','UB','SL','SU'])[((n - 1) % 8) + 1],
       ((n - 1) % 8) + 1 = 7
FROM generate_series(1, 83) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;

-- LHB CC (AC chair car): 78 seats, rows of 5 (3 + 2): window, middle, aisle | aisle, window.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT 'LHB_CC', n, (ARRAY['WS','MS','AS','AS','WS'])[((n - 1) % 5) + 1], false
FROM generate_series(1, 78) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;

-- LHB EC (executive chair car): 56 seats, rows of 4 (2 + 2): window, aisle | aisle, window.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT 'LHB_EC', n, (ARRAY['WS','AS','AS','WS'])[((n - 1) % 4) + 1], false
FROM generate_series(1, 56) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;

-- LHB 2S (second sitting, reserved): 108 seats, rows of 6 (3 + 3): window, middle, aisle | aisle, middle, window.
INSERT INTO reservation.berth_layout (coach_type, berth_no, berth_type, rac_eligible)
SELECT 'LHB_2S', n, (ARRAY['WS','MS','AS','AS','MS','WS'])[((n - 1) % 6) + 1], false
FROM generate_series(1, 108) AS n
ON CONFLICT (coach_type, berth_no) DO UPDATE
  SET berth_type = EXCLUDED.berth_type, rac_eligible = EXCLUDED.rac_eligible;
