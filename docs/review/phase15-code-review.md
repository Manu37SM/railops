# Phase 15: code review and refactoring

**Scope.** All five services, `railops-common`, the test harness, and the observability config.

**Method.** Targeted sweeps were the starting point:
- transaction boundaries around external I/O
- money types and comparisons
- time zones
- HTTP timeouts
- swallowed exceptions
- unbounded reads
- Kafka keys
- duplicated code

Every finding below was confirmed by running code, not by reading alone. Every fix has a test that fails without it. For the fixes that matter most, that failure was checked by putting the bug back.

Findings are ordered by what breaks in production.

## Fixed

| # | Severity | Where | Defect | Production consequence | Fix | Test (verified to fail on the old code) |
|---|---|---|---|---|---|---|
| 1 | **Critical (process)** | `scripts/test-all.sh` | Ran `mvn … \| tee \| grep … \|\| true; rc=${PIPESTATUS[0]}`. After `\|\| true` runs, `PIPESTATUS` describes `true`, so `rc` was always 0. | The Java tiers could never report a failure. In this phase it printed "PASS java integration 56 tests" while all 27 reservation ITs errored. Introduced in Phase 11. The Phase 11–14 results still stand, because those ITs were also run directly and e2e ran against the real jars. But from Phase 11 on, the summary line could not have caught a failure. | Exit code captured before any pipe. Each tier also fails on its own failure/error count from the reports. A build that breaks with no test failures fails both tiers. | Ran against the broken build: **FAIL, 30 failed**. Ran against the fixed build: PASS. |
| 2 | **High (money)** | `PaymentService.createOrGet`, `GatewayPort` | Every "Pay now" called `gateway.createOrder` and overwrote `gateway_order_id`. The port only *assumed* the gateway dedupes by our payment id. The simulator does; real order APIs generally don't. | A customer who pays on an earlier checkout page is captured on an order payment-service no longer knows. `lockByOrder` fails with "unknown gateway order", which is retried forever. Result: **money taken, booking never confirmed, no automatic refund**. | Each payment creates at most one gateway order and reuses the stored one through the new `GatewayPort.checkoutUrl(orderId)`. The bind is `… AND gateway_order_id IS NULL`, so if two callers race, every caller is sent to the winning order. | `retrying_pay_now_reuses_the_order_even_when_the_gateway_does_not_dedupe`. The mock gateway is deliberately non-idempotent. |
| 3 | **High** | `PaymentService.insert` | `DuplicateKeyException` was caught *inside* the transaction and followed by a re-read. PostgreSQL has already aborted that transaction (25P02). | Two concurrent "Pay now" calls for one PNR (double tap, two tabs) gave the loser an HTTP 500. | The re-read happens in a new transaction. | `concurrent_pay_now_for_one_pnr_yields_one_payment_and_one_order_without_errors` (8 threads). The old code reproduced exactly 25P02. |
| 4 | **High** | `NotificationApplication.Notifier` | The listener was `@Transactional` and sent the SMS **inside** the transaction: insert, then send, then commit. | A failed commit, or a crash after the send, caused a **duplicate SMS** on redelivery. That is the opposite of the at-most-once guarantee the class documented. The listener also held a DB connection across the 2.5 s contact lookup and the provider call, so a slow provider could exhaust the 10-connection pool. | No transaction in the listener. The claim row (`SENDING`) commits *before* the provider call, then becomes `SENT` or `FAILED`. A provider error is recorded, not rethrown (no retry storm). Migration V3 only widens the status CHECK, so old and new code can run side by side during a rollout. | `NotificationDeliveryIT`, 3 tests. Putting `@Transactional` back fails "no DB transaction held across the provider call". |
| 5 | **Medium** | payment, notification, identity | Kafka error handlers were hand-copied 4×. Only reservation counted `kafka.dead_lettered`. | The INC-003 alert (`RailopsDeadLettered`) was blind for 3 of 4 consumers. A poison payment event would sit in `…payment.dlt` silently. | `railops-common` `DeadLetters.errorHandler(template, meters, consumer)` is now the one policy for all four services. Consumer names are validated, so DLT topic names stay predictable. | `DeadLettersTest`: DLT topic and partition, counter tags, malformed input not retried. |
| 6 | **Medium** | notification (new failure mode from #4) | At-most-once means a crash between claim and send loses that SMS. | Losses would have been silent. | Gauge `notification.stuck_sending` (claims older than 5 minutes) with alert `RailopsSmsLostAfterClaim`, and `RailopsSmsProviderFailing` on the FAILED rate. | Gauge reads the partial index `ix_delivery_sending`. |
| 7 | **Low** | `RefundService` | `RefundNeedsReview` was keyed by paymentId. Every other `payment.events.v1` event is keyed by PNR. | This event landed on a different partition from the PNR's other events, so per-PNR ordering broke for any future consumer (support UI, ops tooling). | The key is now the PNR. The **payload is unchanged**: the v1 schema has `additionalProperties: false`, so adding a field would break strict consumers and would need a v2. | `a_refund_that_keeps_failing…` asserts every payment event for the PNR shares one key. |
| 8 | **Low** | `PartitionMaintenance` | Runway was computed with `LocalDate.now()` in the JVM's UTC, against IST `run_date` partitions. | The runway gauge read one day too high between 00:00 and 05:30 IST. | Uses `Asia/Kolkata`. | n/a (gauge only; alert threshold is 75 days) |

### A regression caught during this phase

The `StaffRunController` refactor gave `StaffAudit` two constructors and no `@Autowired`, so reservation-service failed to start.
- The broken `test-all.sh` (#1) reported the integration tier as PASS.
- The e2e run caught it, because every request returned 500.
- That led to finding #1.

Both are fixed. The lesson is in the code comment: one constructor per Spring component.

## Refactored (behaviour unchanged)

- **`StaffRunController`.** Went from 189 to 115 lines and from three concerns (HTTP, SQL, audit) to one.
  - The SQL moved **byte-for-byte**, extracted by script rather than retyped, into `RunReportRepository` (MANDATORY propagation, read-only).
  - Audit moved to `StaffAudit`: reads are audited in the reading transaction, actions in their own.
  - Response records moved to `StaffReports` with the same simple names, so the OpenAPI schema names don't change.
  - The `Object[]` money row became the typed `MoneySummary`.
  - Fully-qualified names were replaced by imports.
  - Verified by `OpenApiDriftIT` plus the occupancy, analytics, chart and audit checks in `lifecycle_e2e`.
- **Kafka error handling.** Consolidated (see #5). The orphaned imports were removed.

## Reviewed and deliberately not changed

| Item | Why it stays |
|---|---|
| `forbid_mutation()` in three schemas | Each service owns its schema. A shared function would couple them, and editing applied V1 migrations would break Flyway checksums on every live database. It's intentional duplication, not debt. |
| `catch (RuntimeException)` sites (17) | Each was read. They are in schedulers and per-item loops (HoldSweeper, Reconciler, OutboxRelay, refund worker). Each one logs, counts or records the failure and moves to the next item, so one bad row can't stall a whole batch. None swallow silently. |
| HTTP clients | Every real client sets connect and read timeouts. The simulator's self-callback doesn't, but it is `sim`-profile only. |
| Money comparisons | `compareTo`, never `equals`, on `BigDecimal`. No `double` money anywhere. |
| `.list()` in controllers | Bounded by one run's pools (at most tens of rows). |

## Deferred, with a concrete next step

| Item | Risk if left | Next step |
|---|---|---|
| Real PSP adapter | The simulator hides PSP behaviour | Implement `GatewayPort` for the chosen PSP. Its contract now says explicitly not to rely on dedupe. Run `PaymentMoneyIT` against a sandbox. |
| Kafka SASL/SCRAM + TLS, Memorystore TLS/AUTH | Plaintext inside the VPC. Mitigated by NetworkPolicies and Istio mTLS for pod-to-pod traffic, but not for managed endpoints. | Add `security.protocol=SASL_SSL` through configtree secrets, then turn on Memorystore in-transit encryption and AUTH. Both are config-only. |
| UI "payment received, confirming…" state | A passenger sees PENDING_PAYMENT for a few seconds after paying | Poll the booking after the redirect back, with a capped backoff. |
| Manual-review refund approval UI | `MANUAL_REVIEW` refunds need SQL to resolve | Admin screen plus an approve endpoint with dual control (two admins). |
| PIT mutation testing, nightly | The mutation checks here were manual | Add a pitest-maven nightly CI job on the domain, payment and notification packages, with a score floor. The plugin couldn't be fetched in this offline session. |
| i18n (Hindi + regional), MFA QR code, revenue recognition, catalog-service | Product scope, not defects | Backlog |
| Prediction-log retention job (400 days) | Unbounded table growth | Owner-role job that deletes in batches. Documented in the model card. |

## Verification

| Tier | Result |
|---|---|
| Java unit + contract | 143 (+2) |
| Java integration | 56 (+5), now actually able to fail |
| ML pipeline | 10 |
| Angular | 22 |
| e2e | security 31, lifecycle 32, saga 34, edge 36, ui 21, all passing |
| Inventory invariants | clean |
