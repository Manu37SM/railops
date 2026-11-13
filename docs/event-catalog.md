# RailOps event catalogue

All events use one envelope (`railops-common` `EventEnvelope`):
```json
{ "eventId": "uuid", "eventType": "PaymentCaptured", "schemaVersion": 1,
  "occurredAt": "2026-11-12T02:30:00Z", "aggregateId": "6962089063", "payload": { ... } }
```
Published only through each service's **transactional outbox** (written in the same DB transaction as the
state change, relayed by a single leader per service). Delivery is **at-least-once**; every consumer dedupes
on `eventId` or is naturally idempotent. Payloads carry **no contact PII**; consumers that need it pull it
through a scoped internal API.

## Topics
| Topic | Owner (only producer) | Key | Partitions | Retention | Consumers (group) |
|---|---|---|---|---|---|
| `reservation.booking.v1` | reservation | PNR | 12 | 7 d | payment (`payment-refunds`), notification (`notification`) |
| `reservation.inventory.v1` | reservation | trainRunId | 12 | 2 d | reservation (`reservation-promotion`) |
| `payment.events.v1` | payment | **PNR** | 12 | 7 d | reservation (`reservation-payments`), notification |
| `ops.disruption.v1` | reservation | trainRunId | 6 | 30 d | reservation (`reservation-disruption`) |
| `audit.events.v1` | reservation (more later) | actorId | 6 | 30 d | identity (`identity-audit`) |

**DLTs are consumer-scoped:** `<topic>.<consumer>.dlt`, e.g. `payment.events.v1.reservation.dlt`. Several
services consume the same topic, and each must own and alert on its own failures. DLTs keep 14 days. Retries:
exponential backoff for ~15–30 s, then dead-letter. Parse errors skip retries (they can never succeed).
`auto.create.topics.enable=false`: a misspelt topic fails loudly instead of silently creating one.

Payment events are keyed by **PNR**, not payment id (changed from Phase 2): reservation consumes per booking,
and a failed attempt followed by a successful retry must arrive in order for that booking.

## Events
| Event | Topic | Payload | Meaning / consumer action |
|---|---|---|---|
| BookingInitiated | booking | pnr, trainRunId, class, quota, from/to, totalFare, holdExpiresAt | Seats held; analytics |
| BookingConfirmed | booking | pnr, trainRunId, paymentId, reallocated | Paid; notify passenger |
| BookingExpired | booking | pnr, trainRunId | Hold lapsed unpaid |
| BookingCancelled | booking | pnr, cancellationId, passengers, refundAmount, bookingStatus | Notify |
| RefundRequested | booking | pnr, paymentId, amount, reason, idempotencyKey | payment creates refund (key = at-most-once) |
| PassengerPromoted | booking | pnr, passengerId, newStatus, statusText | Notify |
| WaitlistChartCancelled | booking | pnr, passengers, refundAmount, bookingStatus | Notify |
| ChartPrepared | booking | trainRunId, chartNo, counts | Ops dashboards |
| BookingRunCancelled | booking | pnr, trainRunId, refundAmount, reason | Notify |
| InventoryChanged | inventory | trainRunId, class, quota, from/to, reason | `HOLD_EXPIRED`/`CANCELLED` → WL promotion; search cache (Phase 10) |
| RunCancelled | disruption | trainRunId, reason, requestedBy | Bulk refund job (resumable, cursor-based) |
| PaymentCaptured | payment | paymentId, pnr, amount (captured), currency | reservation confirms, re-seats, or refunds |
| PaymentFailed | payment | paymentId, pnr, reason | Recorded; passenger may retry within hold |
| RefundSucceeded | payment | refundId, paymentId, pnr, amount | Notify |
| RefundNeedsReview | payment | refundId, paymentId, amount | Ops alert after 5 failed attempts |
| StaffAccess / StaffAction | audit | actorId, action, targetType, targetId, occurredAt | Chained into identity's tamper-evident log |

## Verified behaviour (scripts/saga_e2e.py, 34 checks over real Kafka)
Happy path; triple webhook delivery (one capture); forged and replayed webhooks (401); token isolation between
public and internal APIs; decline then retry with a new payment; captured-amount mismatch → refund; late payment
after hold expiry → re-seat; lost webhook → reconciliation; user cancellation refund settling at the gateway;
over-refund blocked to MANUAL_REVIEW; poison message → consumer DLTs without blocking the partition; WL promotion
chain via InventoryChanged; run cancellation with full refunds, no double refund on redelivery; ledger balanced,
no payment refunded past its capture, counters consistent, outboxes drained, staff actions in the audit chain.

## Known gaps
- Schema registry / contract tests between producers and consumers: Phase 11.
- Broker-outage drill (stop Kafka mid-flow, verify outbox drains on recovery): Phase 13 (needs a broker that keeps
  its port across restarts; the local embedded one doesn't).
- Revenue recognition (CUSTOMER_PAYABLE → TICKET_REVENUE/GST at journey completion) is out of payment-service's scope.
- Approving a MANUAL_REVIEW refund (with a fresh budget check) has no UI/API yet.
