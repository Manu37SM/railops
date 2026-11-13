# INC-002: An idle operator transaction blocked bookings on a hot pool

*Drill, Phase 13: `scripts/chaos/db_lock.py` (and `BREAKGLASS=1`). Blameless. Severity if real: **SEV-2** (one
quota of one train unbookable; customer-facing errors).*

## Summary
An operator corrected a capacity value in psql (`BEGIN; UPDATE reservation.segment_inventory …`) and the session stayed
open without COMMIT. Every booking for that train/class/quota waited for the row locks, then failed. Users saw
**500 Internal Server Error** after up to 9 s, which also paged on-call as if it were a code defect. The standard
"blocking tree" query showed nothing, because no request happened to be waiting at that instant. Terminating the
session restored service in 0.2 s.

## Impact (drill)
| | Before fix | After fix |
|---|---|---|
| Bookings on the locked pool | 7 × **500 INTERNAL**, 5 × 503 (pool gate) | 12 × **503 retryable**, `Retry-After: 2` |
| Latency of failures | up to 9 s | up to 6–9 s (gate wait + lock_timeout) |
| Other quota (TQ) on the same train, availability reads | unaffected (201 in ~170 ms, 200 in ~110 ms) | unaffected |
| DB connections held by waiting requests | 1 (PoolGate queued the rest in the JVM) | same |
| Retry with the same Idempotency-Key after recovery | — | 201 (the failed attempt left nothing) |
| Time to recover after the session ends | 0.2 s | 0.2 s; **self-heals** for `ops_breakglass` sessions |

## Timeline (T+ seconds)
| T+ | Event |
|---|---|
| 4 | Operator session takes `FOR UPDATE` locks on run 1 / 3A / GN and goes idle. |
| 4–13 | GN bookings: 500 INTERNAL (lock_timeout 3 s, mapped to the catch-all) or 503 (PoolGate 3 s). |
| 13 | Blocking-tree query: **empty**. Lock-holder query (added): `pid=… user=… app=psql idle=8s tables=segment_inventory`. |
| 40 | `pg_terminate_backend(pid)`. The operator's uncommitted change rolls back. Bookings 201 in ~100 ms. |
| — | Break-glass variant: the session is terminated by PostgreSQL (`idle_in_transaction_session_timeout`), no human action. |

## Root cause
1. **Humans write with unbounded sessions.** The operator connected with a role that has no
   `idle_in_transaction_session_timeout`, so an open transaction could hold hot locks indefinitely. Locally that was the
   application's own role.
2. **Contention surfaced as a defect.** No exception mapping for SQLState `55P03` (lock_timeout), `57014`
   (statement_timeout) or pool exhaustion, so they hit the catch-all → 500, no retry hint, logged as "unhandled".
3. **The diagnosis query looked at waiters, not holders.** Between bursts nothing waits, so the tree is empty while
   the holder is still there.
4. **Follow-up (found by the break-glass run):** with a non-superuser on-call role, `pg_stat_activity` hides other
   users' `state`, `query` and `xact_start`. The holder query silently returned nothing for the actual culprit.
   `pg_read_all_stats` was missing.

## What went well
* **Blast radius stayed at one pool.** Per-pool counter rows plus the Phase 10 `PoolGate` meant other quotas, other
  trains and reads were untouched, and connections didn't pile up.
* `lock_timeout` (Phase 5) bounded every wait; nothing hung.
* Idempotency made the client retry safe.

## Action items
| # | Class | Action | Owner | Priority | Status |
|---|---|---|---|---|---|
| 1 | Prevent | Human roles: `ops_readonly` (read-only, `pg_read_all_data`) for daily use; `ops_breakglass` (NOLOGIN until an incident, `idle_in_transaction_session_timeout=60s`, `lock_timeout=2s`, `statement_timeout=60s`). | Platform/DBA | P1 | **Done** (`10-roles.sh`) |
| 2 | Prevent | App roles: `idle_in_transaction_session_timeout=30s`. | Platform | P2 | **Done** |
| 3 | Mitigate | `55P03` / `57014` / pool timeout → **503 `DEPENDENCY_UNAVAILABLE`, `retryable: true`, `Retry-After: 2`**, logged as WARN (not "unhandled"). Not retried server-side: each retry would wait another lock_timeout. | Reservation | P1 | **Done** |
| 4 | Detect | Lock-holder query in `docs/runbooks/db-lock-contention.md`; `GRANT pg_read_all_stats TO ops_readonly`. | Platform | P1 | **Done** |
| 5 | Detect | Alert: any session idle in a transaction > 30 s holding locks on `reservation.*` (postgres-exporter custom query); booking 503 rate > 5% for 2 min. | Platform | P2 | Rules in `alerts.yaml` |
| 6 | Process | Data fixes go through a reviewed migration or `scripts/ops`, never an interactive write session; break-glass use is logged in the incident channel. | Eng mgmt | P2 | Runbook |
