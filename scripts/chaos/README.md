# Incident drills (Phase 13)

Fault injection against the local stack (`scripts/infra-up.sh`, then `scripts/start-local.sh --fresh`). Each drill
states its hypothesis, injects one fault, logs a T+ timeline, and checks money/inventory invariants afterwards.
Write-ups: `docs/incidents/`.

| Drill | Fault | Run |
|---|---|---|
| `kafka_partition.py` | Kafka unreachable (iptables DROP) while customers pay; `CONTENTION=1` resells freed berths | `RAILOPS_BOOKING_HOLDMINUTES=1 scripts/start-local.sh --fresh` then `CONTENTION=1 python3 -I kafka_partition.py` |
| `db_lock.py` | operator's idle psql transaction holds a hot pool's row locks; `BREAKGLASS=1` uses the time-limited role | `python3 -I db_lock.py` |
| `poison_event.py` | malformed `PaymentCaptured` ahead of a real one; DLT triage and replay | `python3 -I poison_event.py` |

Every drill includes a **control** (something that must still happen normally). A drill without one can pass because
the system stopped working entirely, as INC-001b showed.
