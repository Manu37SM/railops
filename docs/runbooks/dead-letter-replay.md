# Runbook: events in a dead-letter topic

**Alert:** `RailopsDeadLettered` (`kafka_dead_lettered_total` increased). Every dead-lettered event is a customer whose
state did not change. For `payment.*` topics that's money: **page**.

## Triage
```
KAFKA_BOOTSTRAP=… python3 -I scripts/ops/dlt.py list payment.events.v1.reservation.dlt
```
Each line shows the event type, `eventId`, original topic@partition:offset, exception and age. **Old records stay in
the DLT**: work from `eventId`, never from "the first line".

Typical causes:
* `InvalidFormatException` / `JsonProcessingException`: producer bug. Stop the producer (roll back) **first**, or the DLT
  keeps filling.
* `IllegalArgumentException`: the consumer rejected the content (a contract violation). Fix the consumer or the producer.

## Replay
1. Fix the cause (roll back the producer, or deploy a consumer that accepts the payload).
2. Dry run (the default):
   `dlt.py replay <dlt> --event-id <uuid> [--patch '{"field": value}'] --operator <you>`
3. Read the printed audit JSON: target topic, key, eventId, patch from/to.
4. `--yes` publishes with the **same key and eventId**. Consumers deduplicate on eventId, and since INC-003 a capture
   for an already-applied payment is a no-op whatever amount it claims. Replaying twice is harmless.
5. **Money fields** (`amount`, `fare`, `refund`, `price`) can only be patched with `--allow-money-fields`. Use it only
   with a second reviewer, and take the value from the source of truth (payment-service `captured_amount`), never
   from a guess.
6. Paste the audit JSON into the incident ticket.

## Don't
* Don't delete DLT records to make the alert go away. Retention handles cleanup; the records are evidence.
* Don't replay to a different topic or with a new eventId: that turns a replay into a new event and defeats deduplication.
