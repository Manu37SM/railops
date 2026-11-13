# INC-003: A malformed payment event, and a replay that could refund a confirmed booking

*Drill, Phase 13: `scripts/chaos/poison_event.py`. Blameless. Severity if real: **SEV-1 if exploited at scale**
(money loss: seat delivered and fare refunded).*

## Summary
A producer bug published `PaymentCaptured` with `"amount": "2,915.00"`. Reservation's consumer quarantined it to its
DLT without blocking the partition, which is good. But there was no alert, no tooling to inspect the DLT, and
replaying a hand-corrected copy exposed a money defect: **a second capture event for an already-confirmed booking
was refunded** whenever its amount differed from the fare. Only payment-service's refund budget stopped it in the
drill, because the patched amount exceeded the capture. Any amount at or below the capture would have been refunded.

## Impact (drill)
| | Before fix | After fix |
|---|---|---|
| Real payment behind the poison | confirmed in 0.6 s (no head-of-line blocking) | same |
| Detection | none (a WARN log line) | `kafka.dead_lettered{topic}` = 1 → alert |
| Triage | raw Kafka tooling | `scripts/ops/dlt.py list`: type, eventId, origin, exception, age |
| Replay with a wrong amount, booking CONFIRMED | **`RefundRequested AMOUNT_MISMATCH`** (seat + refund) | no-op; ERROR "capture for already-applied payment … claims X" |
| Duplicate capture after cancellation | **second, full refund** under a different idempotency key | no-op |

## Timeline (T+ seconds)
| T+ | Event |
|---|---|
| 0.5 | Malformed `PaymentCaptured` published (same key/partition as a real payment). |
| 0.6 | Customer pays; the genuine event lands behind the poison. |
| 1.2 | Poison → `payment.events.v1.reservation.dlt` (non-retryable parse error); genuine event → CONFIRMED. |
| 3 | `kafka_dead_lettered_total{topic=payment.events.v1}` = 1 (new). |
| 6 | `dlt.py list` shows the record, `InvalidFormatException`, original partition:offset. |
| 10 | Replay with a patched amount, **without** `--allow-money-fields`: refused. |
| 13 | Replay approved; published with the same key and eventId. |
| 17 | Before fix: `RefundRequested` for a CONFIRMED booking. After fix: no-op, ERROR log naming the inconsistent amount. |
| — | Drill bug, kept as a finding: the first replay selected **"the first record listed"**, a stale record from an older run. Replays now select by `--event-id`. |

## Root cause
1. **"Was this capture already applied?" was inferred, not recorded.** `onCaptured` checked the amount against the
   fare *before* the booking status, so any duplicate, corrupted or replayed capture with a different amount for a
   confirmed booking took the mismatch → refund path. The `default` branch refunded duplicate captures on CANCELLED
   bookings under the key `capture-refund:<id>`, which doesn't deduplicate against the cancellation refund.
2. **No DLT operations story:** no metric, no listing, no replay tool, so the first replay in a real incident would
   have been an ad-hoc script.
3. Positional record selection (partition:offset) invites replaying the wrong record. DLTs retain old incidents.

## What went well
* Non-retryable parse errors go straight to a consumer-scoped DLT; the partition was never blocked.
* payment-service's refund budget (Phase 9) stopped this particular over-refund: defence in depth worked.
* The event contract tests (Phase 11) would fail the build for this producer bug. The drill simulated a producer that
  bypassed them.

## Action items
| # | Class | Action | Owner | Priority | Status |
|---|---|---|---|---|---|
| 1 | Prevent | `booking.captured_payment_id` (V9, expand-only, conservative backfill), set wherever a capture is consumed. A capture for an already-applied payment is a strict no-op whatever amount it claims. | Reservation | **P0** | **Done**; ITs `a_replayed_capture_…`, `a_duplicate_capture_after_cancellation_…` (mutation-checked) |
| 2 | Detect | `kafka.dead_lettered{topic,consumer}`. Alert: increase > 0 (page for `payment.*`, ticket otherwise). | Reservation | P1 | **Done** |
| 3 | Recover | `scripts/ops/dlt.py`: list (time-ordered), replay by `--event-id`, dry-run default, money-field patch needs `--allow-money-fields`, JSON audit line. | Platform | P1 | **Done** |
| 4 | Prevent | Same `kafka.dead_lettered` counter in payment and notification error handlers. | Payments, Notification | P2 | Open |
| 5 | Prevent | Contract tests gate producer deploys (Phase 11): a deploy can't skip `*ContractTest`. | Platform | P2 | CI |
| 6 | Process | DLT replay of money events requires two people; the audit JSON is posted to the incident ticket. | Eng mgmt | P2 | Runbook |
