INSERT INTO reservation.train_run (id, train_no, run_date, status, booking_opens_at, tatkal_opens_at, chart1_due_at, chart2_due_at)
VALUES (1, '99001', DATE '2026-11-20', 'SCHEDULED', now() - interval '30 days', now() - interval '1 hour',
        TIMESTAMPTZ '2026-11-20 12:00+05:30', TIMESTAMPTZ '2026-11-20 15:30+05:30');
INSERT INTO reservation.run_stop (train_run_id, stop_seq, station_code, sched_arr, sched_dep, distance_km) VALUES
 (1,0,'MMCT',NULL,                        '2026-11-20 16:00+05:30',   0),
 (1,1,'BRC', '2026-11-20 20:00+05:30','2026-11-20 20:10+05:30', 392),
 (1,2,'RTM', '2026-11-20 23:30+05:30','2026-11-20 23:35+05:30', 653),
 (1,3,'KOTA','2026-11-21 03:20+05:30','2026-11-21 03:30+05:30', 918),
 (1,4,'NDLS',NULL,'2026-11-21 08:30+05:30'::timestamptz - interval '0', 1384);
UPDATE reservation.run_stop SET sched_arr = sched_dep, sched_dep = NULL WHERE train_run_id=1 AND stop_seq=4;
INSERT INTO reservation.run_coach (id, train_run_id, coach_label, coach_type, class_code, position) VALUES
 (1,1,'B1','LHB_3A','3A',5),(2,1,'B2','LHB_3A','3A',6);
-- Tiny quotas so CNF -> RAC -> WL -> REGRET is reachable in a smoke test.
INSERT INTO reservation.segment_inventory (train_run_id, class_code, quota_code, seg_idx, cnf_capacity, rac_capacity)
SELECT 1,'3A',q,s, c, r FROM (VALUES ('GN',3,2),('TQ',2,0)) v(q,c,r), generate_series(0,3) s;
INSERT INTO reservation.waitlist_counter (train_run_id, class_code, quota_code, wl_cap) VALUES (1,'3A','GN',3),(1,'3A','TQ',2);
-- Rows above use explicit ids; move the id sequences past them so runs created later (timetable import, Phase 3B)
-- don't collide with id 1.
SELECT setval(pg_get_serial_sequence('reservation.train_run', 'id'), (SELECT max(id) FROM reservation.train_run));
SELECT setval(pg_get_serial_sequence('reservation.run_coach', 'id'), (SELECT max(id) FROM reservation.run_coach));
