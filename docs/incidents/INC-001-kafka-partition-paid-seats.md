# INC-001: Paid customers lost their seats during a Kafka partition

*Drill, Phase 13: `scripts/chaos/kafka_partition.py` (`CONTENTION=1`). Blameless. Severity if real: **SEV-2** (money
taken, product not delivered; customer-facing).*

## Summary
For 110 s the services could not reach Kafka. Bookings and payments kept working, but `PaymentCaptured` events stayed
in payment-service's outbox. Reservation expired the paid customers' holds after the 1-minute hold window. On a
contended train, one paid customer's berth was resold before the event arrived, and they got a refund instead of a seat.
Nothing alerted. The fix makes hold expiry ask payment-service directly before expiring a hold with a payment in flight.

## Impact (drill scale: 4 paid customers, contended pool)
| | Before fix | After fix |
|---|---|---|
| Paid customers who lost their seat | **1 of 4** (refunded, `LATE_PAYMENT_NO_INVENTORY`) | 0 of 4 |
| Paid customers shown "EXPIRED" | 4 of 4, for ~50 s | 0 |
| Time from heal to outbox drained | 25 s | 5–9 s |
| Alerts fired | none | `outbox.oldest.age.seconds` passed 60 s at T+71 |
| Double-booked berths / unbalanced ledger | 0 / no | 0 / no |

At production scale a 2-minute partition at 10:00 Tatkal would affect every customer who paid in that window. On sold-out
trains most of them would end with a refund instead of a seat.

## Timeline (T+ seconds from drill start)
| T+ | Event |
|---|---|
| 4.5 | Kafka port partitioned (packets dropped). Bookings 201 in ~40 ms; payment intents 201; customers pay. |
| 10 | 5 payments `CAPTURED` in payment DB; 4 bookings still `PENDING_PAYMENT`. All readiness probes green. |
| ~66 | Hold window ends. Sweeper expires all 4 paid holds (`BookingExpired`, berths released). |
| 71 | Rival customers book the freed berths (they're in the pool's free count again). |
| 112 | Partition healed. |
| 137 | Outboxes drained (relay backoff was at its 30 s cap). |
| 137+ | Late `PaymentCaptured` arrive. 3 customers re-seated (berths had freed up again); 1 found none: `FAILED_REFUNDING`. |
| — | **No alert at any point.** Detection would have been customer complaints. |

## Root cause
1. **The sweeper treated "no capture event yet" as "not paid".** Hold expiry consulted only reservation's own state,
   which the event path updates. Any delay in that path, whether partition, broker outage, consumer lag or poison
   message, turned into expired holds for paying customers.
2. The late-payment path (Phase 5) is a correct *recovery*, but on a contended train it can only refund.
3. **Detection:** the `outbox.backlog` gauge reported the size of the last *fetched batch* (capped, frozen during
   backoff), not the backlog. Nothing measured *age*, the signal that tells "stuck" from "busy".
4. **Recovery:** relay backoff capped at 30 s, so up to 30 s of extra delay after the network returned.

## Follow-up defect found while verifying the fix (INC-001b)
The first version of the fix added a repository query without `@Transactional(SUPPORTS)`. The repository is
`MANDATORY` at class level, so the **sweeper threw on every tick and no hold expired at all**. The drill "passed" (no
paid customer lost a seat) for the wrong reason, because abandoned holds would never have expired. It was caught
because `holds rescued` read 0, and the log showed 106 identical stack traces. Two gaps let it through: scheduled-job
failures are only logged (no metric, health stays green), and the drill had no control case.

## What went well
* The transactional outbox did its job: zero lost events, booking latency unaffected (~40 ms), no double booking, ledger
  balanced throughout.
* The late-payment path recovered 3 of 4 customers on its own.
* Consumer deduplication made the late delivery of 20 events uneventful.

## Action items
| # | Class | Action | Owner | Priority | Status |
|---|---|---|---|---|---|
| 1 | Prevent | `HoldSweeper` asks payment-service (`GET /internal/v1/payments/status`, batch, HTTP) before expiring a hold with a payment. `CAPTURED`: confirm via the idempotent capture path. `PENDING`: 2 min grace. Unreachable: up to 15 min grace, never expire blind. | Reservation | P1 | **Done** |
| 2 | Prevent | Booking-path inline cleanup skips holds with a payment in flight (no HTTP inside user requests). | Reservation | P1 | **Done** |
| 3 | Detect | `outbox.backlog` = true count from an independent query; new `outbox.oldest.age.seconds`. Alert: age > 60 s for 1 min (page); > 15 s for 5 min (ticket). | Platform | P1 | **Done** (rules in `deploy/observability/alerts.yaml`) |
| 4 | Detect | `scheduled.task.failures` counter for every `@Scheduled` job. Alert: rate > 0 for 2 min. | Reservation | P1 | **Done** (INC-001b) |
| 5 | Recover | Relay backoff cap 30 s → 5 s. | Platform | P2 | **Done** |
| 6 | Verify | `HoldSweeperIT` (real DB, the scheduled entry point, mutation-checked); drill has a control hold that must expire. | Reservation | P1 | **Done** |
| 7 | Rollout | Register scope `payments:read` for the reservation client in identity **before** deploying reservation, otherwise token requests fail and payment intents break. | Platform | P1 | Runbook |
| 8 | Product | Show "Payment received, confirming your berth…" instead of "EXPIRED" while a capture is pending. | Web | P3 | Open |
| 9 | Refine | Ask the real PSP adapter for "attempted" vs "created" so abandoned checkouts don't get the 2 min grace. | Payments | P3 | Open |
