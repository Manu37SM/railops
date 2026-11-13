# RailOps

RailOps is the booking side of RailLens: it sells seats, takes (test) payments, confirms bookings and sends the
confirmation SMS. Passengers book through the **RailLens** website and Android app. This repo's Angular app is for
**staff** (opening trains for sale, operations, account security).

Since Phase 4D one RailOps account signs in to both RailOps and RailLens.

## What's in here

| Folder | What it is |
|---|---|
| `identity-service` | Accounts, login, MFA, JWT signing (JWKS), service-to-service tokens |
| `reservation-service` | Trains on sale, seat allocation, holds, waitlist/RAC, cancellations, seat map |
| `payment-service` | Payments and refunds: simulated gateway, or Razorpay **test mode** |
| `notification-service` | SMS/notifications from booking events |
| `api-gateway` | The public front door: routing, rate limits, CORS, client-IP handling |
| `railops-web` | Angular staff app |
| `railops-common`, `railops-test-support` | Shared code and test helpers |
| `contracts/` | Event and HTTP contracts the services are tested against |
| `analytics/` | Waitlist-prediction model (Python). **Off** in production: trained on synthetic data |
| `deploy/` | Docker, Docker Compose, Kubernetes (GKE) and **Render** setups |
| `docs/` | Design notes, OpenAPI specs, runbooks, incident write-ups |
| `scripts/` | Local stack, tests, end-to-end suites, secrets |

The services talk through **Kafka** (events, with dead-letter topics) and share **PostgreSQL** (one schema per
service, separate owner and app logins). **Redis** holds short-lived data: rate limits, cached availability and the
waiting room.

Stack: Java 21, Spring Boot 3.5, PostgreSQL 16+, Kafka, Redis, Angular 22.

## Run it locally

You need Java 21, Maven, Node 22, PostgreSQL and Redis. The local-stack scripts expect **Linux or WSL** (they start
PostgreSQL with `service`); on plain Windows use Docker Compose instead. `gen-secrets.sh` also runs in Git Bash.

```bash
scripts/gen-secrets.sh            # once: local secrets into deploy/secrets/ (git-ignored)
bash scripts/infra-up.sh          # starts PostgreSQL, Redis and a local Kafka if not running
mvn -q -DskipTests package        # build the five services
scripts/start-local.sh --fresh    # start them; gateway on http://localhost:8080
cd railops-web && npm ci && npx ng serve    # staff app on http://localhost:4200
```

Or run the whole stack in Docker: see `deploy/compose/` and `docs/deployment.md`.

## Tests

```bash
scripts/test-all.sh          # unit, contract, integration and web tests
scripts/test-all.sh --e2e    # plus the end-to-end suites on a fresh local stack
```

Details: `docs/testing.md`.

## Deploy

- **Render + Neon + Confluent Cloud** (the current plan): `deploy/render/README.md`, step by step.
- **What to deploy first across all RailLens repos**, and the release tracker: `DEPLOYMENT.md`.
- Kubernetes on GKE (later): `deploy/k8s/` and `docs/deployment.md`.

## Secrets: never commit these

`deploy/secrets/`, `deploy/render/render.env` and `deploy/render/out/` hold passwords and keys. They are in
`.gitignore`; check `git status` before every commit anyway.

## Payments

RailOps sells seats from its **own demo inventory**, not Indian Railways tickets. Payments are simulated, or Razorpay
in **test mode**; live Razorpay keys are refused on purpose. Selling real tickets would need IRCTC authorisation.

## More docs

| Topic | File |
|---|---|
| Single login with RailLens | `docs/single-login-plan.md` |
| API style rules | `docs/api-guidelines.md` |
| Kafka events | `docs/event-catalog.md` |
| Staff app structure | `docs/frontend-architecture.md` |
| Performance numbers | `docs/performance.md` |
| Runbooks (Kafka outage, lock contention, dead letters, opening trains, moving accounts) | `docs/runbooks/` |
| Incident write-ups | `docs/incidents/` |
