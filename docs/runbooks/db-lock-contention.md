# Runbook: bookings failing with 503 `DEPENDENCY_UNAVAILABLE` (lock / statement / pool timeouts)

**Alerts:** `RailopsBookingBusy` (503 rate > 5% for 2 min), `RailopsIdleTransactionHoldingLocks`.

Connect as **`ops_readonly`**. It has `pg_read_all_stats`; without it, other users' sessions show NULL state and
query, and query 2 finds nothing (INC-002).

## 1. Who is waiting (only shows something if a request is waiting right now)
```sql
SELECT w.pid, w.application_name, now() - w.query_start AS waiting,
       b.pid AS blocker, b.usename, b.application_name AS blocker_app, b.state, now() - b.state_change AS blocker_idle
FROM pg_stat_activity w
JOIN LATERAL unnest(pg_blocking_pids(w.pid)) bp(pid) ON true
JOIN pg_stat_activity b ON b.pid = bp.pid;
```

## 2. Who is HOLDING locks while idle in a transaction (works even when nobody is waiting)
```sql
SELECT a.pid, a.usename, a.application_name, a.client_addr,
       now() - a.xact_start AS xact_age, now() - a.state_change AS idle_for,
       string_agg(DISTINCT c.relname, ',') AS tables, left(a.query, 120) AS last_statement
FROM pg_stat_activity a
JOIN pg_locks l ON l.pid = a.pid AND l.mode IN ('RowExclusiveLock','RowShareLock','ExclusiveLock','AccessExclusiveLock')
JOIN pg_class c ON c.oid = l.relation AND c.relnamespace = 'reservation'::regnamespace
WHERE a.state LIKE 'idle in transaction%' AND a.xact_start < now() - interval '5 seconds'
GROUP BY 1,2,3,4,5,6,8 ORDER BY xact_age DESC;
```

## Mitigate
* **Human session** (`psql`, `ops_breakglass`, a BI tool): `SELECT pg_terminate_backend(<pid>);`. The uncommitted
  change rolls back; tell its owner. Break-glass sessions end themselves after 60 s idle.
* **Application session** idle in a transaction is a bug: the app role ends it after 30 s. Capture the
  `last_statement`, terminate, and open an incident for the code path.
* **Not lock-related** (no holders; `hikaricp_connections_pending` > 0): pool exhaustion. Check
  `inventory_pool_lock_hold_seconds` p99 and `inventory_pool_gate_rejected_total` (hot pool), then slow queries
  (`log_min_duration_statement`).

## Verify
Booking 503 rate back to ~0. Clients that got 503 retry with the same `Idempotency-Key`; that's safe, because a
failed attempt leaves nothing behind.
