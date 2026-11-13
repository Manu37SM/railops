# RailOps — Testing Strategy (Phase 11)

```
scripts/test-all.sh          unit + contract + integration + web   (no running stack)
scripts/test-all.sh --e2e    ... plus every end-to-end suite on a freshly reset stack
```

## 1. Tiers

| Tier | Where | Runs on | What it proves | Count |
|---|---|---|---|---|
| Unit | `*Test` (surefire, `mvn test`) | nothing | pure rules: allocation planning, refund policy, PNR, crypto, ABAC, JWT mapping, PII masking, pool gate, waiting-room order | 70 |
| Contract | `*ContractTest` (run with unit) | nothing | producers emit what `contracts/` says; consumers accept what producers emit | 44 |
| Integration | `*IT` (failsafe, `mvn verify`) | real PostgreSQL (+ embedded Kafka) | concurrency, transactions, SQL, DB constraints, idempotency, money invariants | 39 |
| Web | Vitest | Node | Angular services, interceptors, clock skew | 18 |
| End-to-end | `scripts/*_e2e.py` | full stack | real HTTP, Kafka, Redis, gateway, browser UI | 147 |

The first two rows share one 114-test surefire run (several contract classes are parameterised, so the split is
by assertion target, not class count).

**Rule for where a test goes:** test a rule at the lowest tier that can see it break. Allocation arithmetic is a unit
test. "No two passengers on one berth under 30 concurrent requests" is only visible with a real database, so it's
integration. "The SMS reaches the phone after Kafka" needs the whole stack, so it's e2e.

## 2. Integration tests use real PostgreSQL, never H2

The correctness of this system rests on PostgreSQL features: GiST exclusion constraints over `int4range`, monthly
partitions, `FOR UPDATE` lock ordering, advisory locks, deferred constraint triggers and `ON CONFLICT`. An emulator
that lacks them would pass tests that production fails.

`ItPostgres` gives each test class a throwaway database:

* `RAILOPS_IT_PG=jdbc:postgresql://host/postgres?user=..&password=..`: a database is created on that server (CI uses
  a service container; locally, the dev PostgreSQL with a `CREATEDB` role);
* otherwise Testcontainers `postgres:16-alpine`.

Spring contexts run with timers, Kafka listeners and topic creation off (`railops.scheduling.enabled=false`). Tests
call jobs (sweeper, refund worker, partition maintenance) **explicitly**, so a test never races a timer. Only the
external payment gateway is mocked; everything we own runs for real.

## 3. Contracts (`contracts/`)

* `contracts/events/<Type>.v1.schema.json`: JSON Schema 2020-12 for all 14 event payloads, and
  `contracts/events/examples/`: golden payloads.
* `contracts/http/<consumer>-to-<provider>.<name>.json`: one recorded interaction per internal HTTP call.

| Side | Event test | HTTP test |
|---|---|---|
| Producer | serialise the **real record** with the **service's own ObjectMapper** (from its `application.yml`), validate against the schema | replay the fixture request into the real controller; response shape must match |
| Consumer | parse golden examples with the real listener types; templates render complete messages | point the real client at a stub serving the fixture; check what it sent |

Integration tests also validate **every outbox row written by real transactions** against its schema. That covers
payloads assembled deep in services, not just hand-built samples.

Why not Pact/Spring Cloud Contract: two internal HTTP calls and one team don't justify a broker. The fixture files are
the same idea (consumer-driven, both sides verified) without the infrastructure. Revisit when teams split.

## 4. What the tests found (fixed in this phase)

| # | Finding | Severity | Found by |
|---|---|---|---|
| 1 | **Partitions were never extended.** Created once at migration, 4 months ahead; bookings for later dates would fail with "no partition", a few months after deploy, with no code change. Added `PartitionMaintenance` (startup + daily, advisory-locked, runway gauge). | **Outage** | writing `DatabaseGuaranteesIT` |
| 2 | **Wire format depended on converter order.** `jackson-dataformat-yaml` (via springdoc) is on the classpath and `PaymentClient` never set a content type: with a different converter order, reservation sends payment-service YAML. Now explicit JSON on every client. | High | `PaymentHttpConsumerContractTest` |
| 3 | **Notification would text blanks.** Templates read with `path().asText()`; a renamed field produced "Refund Rs  initiated." to a customer. Templates now require fields; a missing one goes to the DLT. | Medium (customer-facing) | contract review |
| 4 | **Untyped event payloads.** 7 events were `Map.of(...)`, invisible to the compiler and tests. Now records. | Medium | contract review |
| 5 | **API docs drifted.** Run cancellation and all 4 waiting-room endpoints were missing from `reservation-v1.yaml`. | Medium (clients) | `OpenApiDriftIT` |
| 6 | **Waiting-room tie.** Early-joiner score added a random *double*, which could round up to `opensAt·1000` and tie with someone joining at the opening millisecond (~1e-10 per join). Now integer-exact. | Low | `WaitingRoomScoreTest` |
| 7 | **RUN_STATUS events omit keys.** With `non_null` inclusion, class/quota are *absent*, not null; the schema now says so. | Low | producer contract test |

## 5. Do the tests bite? (mutation checks)

A test that has never failed proves little. The two most important tests were run against deliberately broken code:

| Mutant | Test | Result |
|---|---|---|
| Outbox relay without its advisory-lock leader election | `OutboxRelayIT.two_relays_polling_concurrently…` | **killed** (duplicate publications) |
| Refund budget read without `FOR UPDATE` | `PaymentMoneyIT.concurrent_refund_requests…` | **killed**: Rs 2,000 of refunds approved on a Rs 1,000 capture |

Next step (Phase 15): run PIT on `domain.policy` and `payment.service` in a nightly job and track the score.

## 6. Money, auth and PII coverage (flagged)

* **Money:** refund budget under concurrency; redelivered refund requests; uncaptured payment refunds go to review;
  ledger cannot be unbalanced or edited (database-enforced); wrong-amount, stray, duplicate and late captures;
  webhook signature and staleness; paise preserved on the wire; reservation refuses an intent with a different amount.
* **Auth:** refresh-token theft revokes the family; concurrent tabs aren't treated as theft; 20 concurrent bad
  passwords evaluate exactly 5 (row lock proven) and lock the account; audit chain detects an owner-level edit;
  hostile JWT claim values aren't mapped to authorities; malformed ABAC grants deny.
* **PII:** encryption is randomised, column-bound (AAD), tamper-evident and rotatable; log masking covers +91 and
  embedded formats without masking PNRs.

## 7. Known gaps

* **No load/soak tier in CI.** `bench_*` scripts are manual (performance.md).
* **The audit chain detects, it does not prevent, a full rewrite.** Covered by the WORM head export (Phase 12).
* **Gateway rate limits are e2e-only.** Not unit-tested at the filter level.
* **E2E needs a full stack.** Not on every PR until Phase 12's compose file lands.
* **Notification's SMS is sent inside the DB transaction.** A commit failure after a send means the redelivery sends
  again (at-least-once SMS). Accepted for now; documented.

## Phase 14: ML tier

| Test | Guarantees |
|---|---|
| `analytics/tests/test_pipeline.py` (tier `ml`) | Split by run date; per-train priors never see test rows; features contain no label; clipping bounds; monotonicity gate rejects a bending model; bundled model + golden file from one run and marked SYNTHETIC |
| `WaitlistModelTest` | Java recomputes all 43 golden cases (features and probability) within 1e-9 of Python; worse WL position never scores higher (2,000 random pools); fail-fast loading on feature order, version, array length, non-monotone calibration map, missing clips |
| `PredictionIsolationTest` | Allocation, promotion, chart, booking, cancellation, payment and domain classes never import the prediction package |
| `WaitlistPredictionIT` | Serve → log (once per 6 h bucket, no PII) → cancel/promote/chart → outcome resolved (CONFIRMED / NOT_CONFIRMED / VOID) → Brier from stored probabilities; resolution idempotent |
| `lifecycle_e2e` (+7) | Owner-only estimate, public next-WL estimate, post-chart "no estimate", staff analytics WL funnel and scope 403 |
| `wl-chance.spec.ts` | Rounded to 5%, never 0/100%, band + disclaimer; omitted `probability` (non_null JSON) renders nothing, not NaN% |

Retrain: `scripts/ml-train.sh` (retrains, installs model + golden cases, runs both sides of the parity test).

## Phase 15 additions

- `test-all.sh` now actually fails: the Maven exit code was being read from `PIPESTATUS` after `|| true` (always 0). Each tier also fails on its own failure/error counts. Verified by running it against a broken build.
- `PaymentMoneyIT`: one gateway order per payment against a deliberately non-idempotent gateway; 8-thread concurrent create for one PNR.
- `NotificationDeliveryIT`: claim committed and no transaction open during the provider call; provider failure recorded and never resent; a crash-left claim is not resent.
- `DeadLettersTest`: the shared consumer error policy (DLT name/partition, counter, no retries for malformed input).
