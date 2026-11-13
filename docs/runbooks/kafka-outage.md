# Runbook: Kafka unreachable / events delayed

**Alerts:** `RailopsOutboxStuck` (oldest unpublished event > 60 s), `RailopsHoldStatusChecksFailing`.

## What users experience
Booking, payment and cancellation keep working (transactional outbox). Confirmations, promotions, notifications and
the availability cache lag behind. Paid holds are protected: the sweeper asks payment-service directly (INC-001) and
confirms them, so `booking_hold_rescued_total` rising is the system working.

## Check
1. `outbox_oldest_age_seconds{schema}` and `outbox_backlog{schema}`: one schema or all? All = broker/network; one =
   that service's producer (look for `outbox relay: producer MISCONFIGURED` in its log, Phase 9).
2. Broker health (Managed Kafka console / `kafka-broker-api-versions.sh`) and network (NetworkPolicy, Private Service
   Access route).
3. Consumer lag per group (`reservation-payments`, `reservation-inventory`, `notification`): lag with an empty outbox
   = a consumer problem, not Kafka.

## Mitigate
* Nothing to do in the services: they retry with ≤ 5 s backoff and drain on their own (drill: 5–9 s after heal).
* **Do NOT** restart services to "unstick" them: it doesn't help, and it resets the relay backoff state for nothing.
* If the outage passes ~15 min: holds whose payment status is also unknown start expiring (`unknown-grace`). Captures
  arriving later take the late-payment path (re-seat or refund). Watch `payment_refund_*`.

## After
* `outbox_backlog` back to 0 everywhere; consumer lag back to 0.
* `SELECT status, count(*) FROM reservation.booking WHERE updated_at > now() - interval '1 hour' GROUP BY 1;`
  shows no unexpected `FAILED_REFUNDING` spike.
