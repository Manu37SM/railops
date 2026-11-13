\echo '--- I1: overlapping live allocations on the same berth half (must be 0)'
SELECT count(*) AS double_booked FROM reservation.allocation a JOIN reservation.allocation b
  ON a.run_coach_id=b.run_coach_id AND a.berth_no=b.berth_no AND a.id<b.id
 AND a.seg_range && b.seg_range AND a.slot_range && b.slot_range;
\echo '--- I2: counters vs. actual allocations per segment (rows shown = drift; must be empty)'
WITH actual AS (
  SELECT s.quota_code, s.seg_idx, s.cnf_used, s.rac_used,
         count(a.id) FILTER (WHERE a.slot_range='[1,3)') AS full_alloc,
         count(a.id) FILTER (WHERE a.slot_range<>'[1,3)') AS rac_alloc
  FROM reservation.segment_inventory s
  LEFT JOIN reservation.allocation a ON a.train_run_id=s.train_run_id AND a.class_code=s.class_code
       AND a.quota_code=s.quota_code AND a.seg_range @> s.seg_idx::int
  WHERE s.train_run_id=1 GROUP BY 1,2,3,4)
SELECT * FROM actual WHERE cnf_used<>full_alloc OR rac_used<>rac_alloc;
\echo '--- utilisation per quota/segment'
SELECT quota_code q, seg_idx s, cnf_used||'/'||cnf_capacity cnf, rac_used||'/'||rac_capacity rac FROM reservation.segment_inventory ORDER BY 1,2;
\echo '--- WL numbers: issued vs entries (gaps allowed, duplicates not)'
SELECT quota_code, count(*) entries, count(DISTINCT wl_seq) distinct_seq, max(wl_seq) FROM reservation.waitlist_entry GROUP BY 1;
\echo '--- RAC pairing: berths with both halves used vs single'
SELECT count(*) FILTER (WHERE n=2) paired, count(*) FILTER (WHERE n=1) single
FROM (SELECT run_coach_id, berth_no, seg_range, count(*) n FROM reservation.allocation WHERE slot_range<>'[1,3)' GROUP BY 1,2,3) x;
