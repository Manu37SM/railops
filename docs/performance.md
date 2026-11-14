# RailOps — Caching, Performance and Edge Protection (Phase 10)

All numbers measured on the development VM: **2 vCPU shared** by the load client, PostgreSQL, Redis, Kafka and five
JVMs. Treat absolutes as a floor; the *ratios* and the *bottleneck identification* are the transferable results.
Reproduce with `scripts/bench_availability.sh` and `scripts/bench_booking.py`.

## 1. Availability (read path)

| Mode | Throughput | p99 | Server CPU / request |
|---|---|---|---|
| PostgreSQL only | 765 req/s | 388 ms | 2.41 ms |
| Redis read model (one `HGETALL` per run) | 1,311 req/s | 164 ms | 1.54 ms |
| Redis + 500 ms in-process micro-cache | 1,795 req/s | 90 ms | 1.11 ms |
| *Client ceiling (health endpoint)* | *2,186 req/s* | | |

* **Design.** One Redis hash per run (`ro:run:{runId}`): `status` plus one field per pool,
  `p:CLS:QUOTA = "c0,c1;r0,r1;nwl;wlr"` (per-segment CNF/RAC free, next WL, WL remaining). A journey query is
  computed in the JVM from one round trip. The first design (three round trips per query) was *no faster than the
  database* and was discarded.
* **Freshness.** Fed by `InventoryChanged` events; every event recomputes the whole pool from PostgreSQL (no deltas, so
  a lost or duplicated event can't drift the model). Measured lag: **0.59 s** booking → visible. Plus up to 500 ms
  micro-cache.
* **Why staleness is safe.** Availability is advisory. Every booking re-measures under the pool's row locks, so a stale
  number can disappoint ("AVAILABLE-2" then waitlisted), never oversell.
* **Failure.** Redis errors open circuit breaker `redis`; reads fall back to PostgreSQL through a bulkhead of 8
  concurrent queries, beyond which callers get 503 instead of melting the booking database.
  Single-flight load per run; TTL jitter so runs don't expire together.

## 2. Booking a single hot pool (write path)

Every request books the same run/class/quota: the worst case, since the pool's `segment_inventory` rows serialise
all bookings. Phase 3 predicted **~100–200 allocations/s per pool**.

| Concurrency | Bookings/s | p99 | Mean pool-lock hold |
|---|---|---|---|
| 1 | 69 | 59 ms | **7.0 ms** → ceiling ~142/s |
| 4 | 88 | 129 ms | 11.6 ms |
| 16 | 88 | 762 ms | 11.7 ms |
| 32 | 77 | 1,459 ms | 14.5 ms |

12 coaches of 3A (768 berths), 1 passenger per booking, warm JVM.

* **The limit is the lock, confirmed by sampling, not inferred.** During the runs, PostgreSQL sessions were almost
  entirely in `Lock:tuple`, and measured throughput matched `1 / mean(lock hold)` to within a few percent.
* **The uncontended hold of 7 ms matches the Phase 3 prediction** (ceiling ~142/s). Under load the hold stretches,
  because this box is CPU-saturated (load client included) and the lock holder waits for CPU. On a dedicated database
  host, expect the hold to stay close to the uncontended figure.
* **New metric: `inventory.pool_lock.hold`** (timer, p50/p99). It is THE capacity metric for a pool: alert when
  `1 / mean` approaches the waiting-room admission rate.

### Changes made from the measurement

1. **Work moved out of the locked section.** ID allocation, PNR claim, contact encryption and fare JSON now happen
   before `lockAndMeasure`. The gain was within run-to-run noise on this box (~±10%), but it is free and strictly
   shortens the critical section.
2. **`PoolGate`**: at most 4 concurrent allocation transactions per pool per instance; the rest wait in the JVM (fair
   semaphore, max 3 s, then 503 "nothing was booked, retry").

   | 32 concurrent bookers | Bookings/s | p99 |
   |---|---|---|
   | gate off | 65 | 1,756 ms |
   | gate 2 | 78 | 608 ms |
   | gate 4 | 68 | 849 ms |

   **Honest reading:** no reliable throughput gain (noise ±10%), but a **2–3× lower p99**, and a hot pool can no
   longer pin dozens of DB connections that other pools need. Per-instance, so N instances allow N×4; that's
   acceptable because the waiting room paces arrivals globally.

### What this means for Tatkal

A 3A pool holds ≤ ~800 berths, so at 70–140/s it sells out in **6–12 s**. Per-pool throughput isn't the user-visible
problem; arrival fairness is. Set the waiting-room `admitPerSecond` for a run to **≤ 50% of the pool ceiling summed
over the run's Tatkal pools**, so admitted users never queue on locks.

Real headroom, if ever needed, comes from sharding the *work*, not the lock: batch several queued requests into one
transaction per lock acquisition (group commit). That was deliberately not built: it complicates idempotency and
per-request errors, and the data doesn't need it.

## 3. Tatkal waiting room

* **Fairness rule.** Join before `opensAt` → uniformly random place among early joiners. Join after → FIFO behind them.
  Arriving 50 ms earlier buys nothing, so bots lose their main advantage.
* **Mechanics.** ZSET per run; scores `opensAt·1000 − 1e9 + rand(1e9)` (early) or `now·1000` (late), added with NX
  so re-joining keeps your place. A 1 s tick with a per-room leader key runs a Lua script that pops and grants
  admissions atomically, so a crash can't lose people from the queue. Admission lasts 10 minutes.
* **Gate.** Bookings for a run with an active room need an admission key, else 403 `RES_WAITING_ROOM`.
* **Failure policy.** Redis unreachable → **Tatkal fails closed (503)**, because a Redis outage at 10:00 must not
  release the stampede onto the database. Other quotas fail open.
* **Kill switch.** `DELETE /api/v1/staff/runs/{id}/waiting-room` (controller in scope or admin).

## 4. API gateway (Spring Cloud Gateway, WebFlux, port 8080)

| Route | Limit (token bucket) | Key |
|---|---|---|
| `POST /api/v1/auth/login`, `/register` | 1/s, burst 5 | client IP |
| `POST /api/v1/bookings` | 1/s, burst 3 | verified user |
| `GET /api/v1/runs/*/availability` | 10/s, burst 30 | client IP |
| everything else | 20/s, burst 40 | verified user, else IP |

* **Blocked at the edge (404):** any path with a segment `internal`, `actuator`, `oauth2`, `sim-gateway` or `..`.
  This is checked on decoded, case-folded segments (tests cover `/INTERNAL/`, `%2e%2e` and `../` traversal).
* **Rate-limit keys use a signature-verified `sub`.** An unverified `sub` would let an attacker forge a victim's
  id and spend the victim's budget. A test proves a forged token with the victim's `sub` lands in the attacker's IP
  bucket.
* **Not the authorization layer.** Services still validate tokens, audiences, roles and ABAC scope.
  The gateway only adds what is cheapest at the edge.

## 5. Risks and open items (flagged)

* **Auth: rate limits fail open when Redis is down** (SCG behaviour). Login brute force then falls back to identity's
  per-account lockout, which stops guessing against one account but not slow spraying across many accounts. If
  stronger protection is needed, add an in-memory per-instance fallback limiter.
* **Auth: client IP = TCP peer.** Behind a cloud load balancer every request would share the LB's IP and one login
  bucket. Configure the trusted proxy hop (`server.forward-headers-strategy` plus the LB's ranges) at deployment
  (Phase 12). Never trust a raw `X-Forwarded-For`.
* **Money: one admission allows several bookings for 10 minutes**, bounded only by the per-user booking limit
  (burst 3). Consider consuming the admission on the first successful booking per run (`DEL` in the same flow) if
  agents abuse it.
* **Money: the local benchmark mutates inventory** (capacities, extra coaches). `reset-local.sh` now restores the
  seeded train. This was the cause of a Phase 9 suite passing only on polluted state; it now provisions explicitly.
* **Version-dependent:** measured on Spring Cloud 2025.0.3 (Gateway 4.3) with Boot 3.5.x; since 14 Nov 2026 the
  code runs Spring Cloud 2025.1.4 (Gateway 5.0) with Boot 4.1. The property namespace
  `spring.cloud.gateway.server.webflux.*` is the same. Re-measure before relying on these numbers.
* **Measured on 2 shared cores:** re-run both benchmarks on production-shaped hardware before setting
  `admitPerSecond` or the alert thresholds.
