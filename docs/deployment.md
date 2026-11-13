# RailOps — Containers and Deployment (Phase 12)

```
deploy/docker/service.Dockerfile   one multi-stage image definition for every Spring Boot service
deploy/docker/web.Dockerfile       Angular SPA on nginx-unprivileged (+ nginx.conf: CSP, /api proxy)
deploy/compose/                    full stack for integration/demo/CI (sim PSP), Postgres role bootstrap
deploy/k8s/base, overlays/{staging,prod}, components/istio-mtls     GKE (Kustomize)
scripts/gen-secrets.sh             compose secrets (random, git-ignored)
scripts/deploy_smoke.py            post-deploy smoke test through the public origin (+ operator probes)
scripts/compose_sim.py, web_edge.py   run the compose configuration without a Docker daemon (see §7)
```

## 1. Images

| | Service images | Web image |
|---|---|---|
| Base | `eclipse-temurin:21-jre-noble`, pinned by digest | `nginxinc/nginx-unprivileged:1.28-alpine`, pinned by digest |
| User | uid/gid 10001, non-root | uid 101 |
| Layers | Boot layers: dependencies (~72 MB) → loader → snapshots → application (~1 MB) | static bundle |
| Filesystem | read-only root; writable `/tmp` only | read-only; `/tmp` and nginx cache |
| JVM | `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, `user.timezone=UTC`, bounded metaspace | — |
| Health | `/actuator/health/{liveness,readiness}` (probes enabled everywhere) | `/healthz` |

Digests were resolved on 2026-11-12. Renovate or Dependabot should bump them; the Dockerfiles never float tags.
Tests run in CI before the image build. The image build only packages.

## 2. Configuration audit (pre-containerisation)

| # | Finding | Production consequence | Fix |
|---|---|---|---|
| 1 | The runtime DB user ran Flyway, so it owned the schema | App could `DROP`, `ALTER`, and **disable the audit/ledger immutability triggers** | Owner role (migrations only) vs DML-only app role; `V*__runtime_grants.sql`; append-only tables get only `SELECT, INSERT`; partition DDL through a `SECURITY DEFINER` procedure |
| 2 | Migrations ran inside the app | Every pod held owner (DDL) credentials | `in.railops.common.db.Migrate` as a one-shot (compose) or init container (k8s); apps run with `spring.flyway.enabled=false` |
| 3 | Simulators tied to `@Profile("local")` | No non-local profile could take a payment or price a fare | Feature profile `sim`; `local` = group(`sim`) |
| 4 | Client `X-Forwarded-For` reached identity | **Audit-log IPs and login rate-limit keys were client-chosen** | Gateway resolves the client IP right-to-left over trusted proxies only, drops `Forwarded`/`X-Forwarded-*`, sets exactly one value |
| 5 | Gateway actuator exposed `gateway` | Routes can be created via `POST /actuator/gateway/routes` | Removed |
| 6 | Pool sizes 40/20/20/10 per replica | 3 reservation replicas alone exceed PostgreSQL's default 100 | Explicit budget (§4) |
| 7 | notification-service had no DB timeouts or graceful shutdown | Hung connections; killed in-flight work | Aligned with the others |
| 8 | Secrets only as env vars | Visible in `docker inspect`, `/proc/*/environ`, crash dumps | `configtree:/run/secrets/` files |
| 9 | springdoc enabled by default | API docs in every environment | Default off; on in `local` |
| 10 | `identity.login` success/failure meters had different tag keys | **Prometheus silently dropped failed-login metrics**, so brute-force alerts could never fire | Same tag keys |

**Already correct:** the JWT key fails fast outside local/test, `ddl-auto: validate`, `open-in-view: false`, and
statement/lock timeouts on 3 of 4 services.

**Not resolvable here:** values in GCP Secret Manager; real PSP and catalog endpoints (neither exists yet, see §8).

## 3. Secrets

| Where | How |
|---|---|
| Local dev | `application-local.yml` throwaway values (unchanged) |
| Compose / CI | `scripts/gen-secrets.sh` → `deploy/secrets/` (git-ignored), bind-mounted as `/run/secrets/<property.name>` |
| GKE | GCP Secret Manager → External Secrets Operator → Kubernetes Secret → mounted files. Workload Identity everywhere; no JSON keys |

Each service sees **only its own** secrets. Owner DB passwords are mounted only into migrators.
`gen-secrets.sh` is idempotent: it never rotates a key that already encrypted data.

**Compose permissions gotcha (found here):** compose file secrets are bind mounts that keep host permissions, and
each container reads as its own uid. The files are `0644` inside a `0700` directory, so other host users can't
reach them while containers can. With `0600`, PostgreSQL's init could not read the passwords, and the roles were
created **without passwords**; the init script then continued silently. The init script now validates every secret
before use and aborts if one is missing.

Rotation:
* field-encryption keys add a key id (old rows stay readable, re-encrypted lazily);
* the JWT key is rotated with the old public key kept in `previous-public-keys-pem` for one access-token lifetime;
* DB passwords use the two-step rotation that Secret Manager versions and Cloud SQL support.

## 4. Database connection budget

| Service | Pool/replica | Replicas (min–max) | Peak |
|---|---|---|---|
| reservation | 20 | 3–12 | 240 |
| identity | 10 | 2–4 | 40 |
| payment | 10 | 2–4 | 40 |
| notification | 5 | 2–3 | 15 |
| migrators, ops, replication headroom | | | 30 |
| **Total** | | | **365 of Cloud SQL `max_connections=400`** |

The HPA maximum for reservation is set by this table, not by the cluster. Raising it means raising the pool budget
or putting PgBouncer (transaction mode) in front. The app's per-pool `PoolGate` (Phase 10) already queues hot-pool
work in the JVM instead of on connections.

## 5. Compose

* `docker compose -f deploy/compose/docker-compose.yml up -d --build --wait` brings everything up after
  `scripts/gen-secrets.sh`.
* Two networks. `edge` holds web and gateway, and is the only published network (8088 web, 8080 API). `backend` is
  `internal: true`, so databases, Kafka, Redis and `/internal/**` have no route to the host.
* Startup order is enforced by health: postgres → `<svc>-migrate` (completes) → service (healthy) → gateway → web.
* Every container runs with `read_only`, `cap_drop: ALL` and `no-new-privileges`, with memory and CPU limits.
* `ALLOWED_ORIGINS` **must equal the public web origin**. Otherwise every browser POST gets a CORS 403; API
  clients (no `Origin` header) are unaffected, which hides the problem from API-only tests.

## 6. Kubernetes (GKE)

* **Pods:** Pod Security `restricted`; non-root; read-only root filesystem; `drop: ALL`; seccomp `RuntimeDefault`;
  no service-account token mounted.
* **Start order inside a pod:** Cloud SQL Auth Proxy (native sidecar) → `migrate` init container (owner secret) →
  app (DML secret).
* **Probes:** startup (≤ 2 min) gates liveness; liveness checks the process only, never dependencies (otherwise a
  DB blip restarts everything); readiness includes the DB.
* **Rollout:** `maxUnavailable: 0`, `maxSurge: 1`. `preStop` sleeps 10 s, then graceful shutdown (30 s), within a
  50 s grace period. PodDisruptionBudgets everywhere; pods spread across zones.
* **Autoscaling:** CPU-based HPA for reservation, gateway and web. Reservation scales down slowly (10-minute window)
  so the 10:00 and 11:00 Tatkal waves don't meet a shrunken fleet.
* **Network:** default-deny NetworkPolicy plus one allow-list per workload, including Private Google Access for the
  Cloud SQL Admin API, metadata server, DNS and istiod.
* **mTLS** (`components/istio-mtls`): STRICT PeerAuthentication. AuthorizationPolicies allow `/internal/*` only from
  the intended caller's SPIFFE identity (notification → reservation contact API; reservation → payment), and
  `/oauth2/token` only from service clients.
* **Edge:** Gateway API on the global external ALB with Certificate Manager. Cloud Armor (WAF and per-IP limits)
  via `GCPBackendPolicy`. Container-native LB (NEGs).
* **Client IP chain on GKE:** Google's ALB sends `X-Forwarded-For: <client-supplied…>, <client>, <LB IP>`. nginx
  appends the GFE address, and the gateway walks right-to-left over `RAILOPS_EDGE_TRUSTED_PROXIES` (GFE ranges, pod
  CIDR, LB IP) to the client. Replacing the header in nginx (my first version) would have given every user the same
  GFE address: one shared login rate-limit bucket.
* **Prod overlay** pins image digests (CI checks it), and has **no `sim` profile**: see §8.

Validated here: every core manifest passes strict schema validation against Kubernetes 1.33. A negative control
(typo field, wrong type) was rejected, and every overlay patch resolves to an existing resource and path. Not run
here: `kustomize build` and kubeconform with CRD schemas. Downloading those binaries was blocked in this
environment, so they run in CI (`manifests` job).

## 7. How this was verified without a Docker daemon

This environment has no Docker daemon. Instead of shipping unexecuted configuration, `scripts/compose_sim.py`
runs the stack **from `docker-compose.yml` and `service.Dockerfile` themselves**:

* per-service environment, secrets (`source → target`) and profiles are read from the compose file;
* `JAVA_TOOL_OPTIONS` is read from the Dockerfile;
* each JVM starts from the extracted Boot layers (same entrypoint), as uid 10001, with a root-owned (read-only) app
  directory;
* PostgreSQL roles are created by the real `10-roles.sh`; Redis requires a password; migrators run first;
* the web tier is emulated by `web_edge.py`, which reads its security headers and proxy rules from `nginx.conf`.

Differences from a real Compose run: host ports instead of DNS names, the host's PostgreSQL and Kafka, and nginx
emulated. CI (`compose-e2e`) runs the real thing with the same smoke test.

**Results: `deploy_smoke.py` passes 28/28.** It covers:
* the shipped CSP in a real browser: zero violations while walking search → register → logged in;
* register, login (HttpOnly, Secure, SameSite=Strict refresh cookie), booking, the signed-webhook payment saga over
  Kafka, and cancellation with refund;
* edge blocking;
* client-IP spoofing defeated, checked in the audit log;
* bootstrap admin from secret files with MFA, and JWKS serving the mounted key.

**Database privileges, probed as the runtime roles:** DDL, cross-schema reads, updating or deleting append-only
tables, disabling triggers, and writing Flyway history are all refused by PostgreSQL. Partition maintenance still
works through the `SECURITY DEFINER` procedure.

**SIGTERM:** Kafka consumers stop, Tomcat drains, and the process exits in 0.8 s.

**Defects found only by actually running it:**
* passwordless DB roles (secret permissions combined with a silent shell failure);
* `CREATE SCHEMA IF NOT EXISTS` still needs database `CREATE`;
* the `ALLOWED_ORIGINS` coupling;
* dropped Prometheus series;
* stale `railops-common` inside incrementally built fat jars (`forceCreation`);
* Angular's critical-CSS inliner emitting an inline script that the CSP would block.

## 8. Not production-ready yet (deliberate, loud failures)

* **No real PSP adapter.** Outside `sim`, payment-service has no `GatewayPort` bean and refuses to start. Production
  needs a Razorpay/PayU/… adapter: order creation, webhook signature scheme, refunds, reconciliation API.
* **No catalog-service.** Outside `sim`, reservation prices fares via `HttpFareClient` → `catalog-service`, which
  doesn't exist.
* **Memorystore TLS.** In-transit encryption needs `spring.data.redis.ssl.bundle` with the Memorystore CA (an SSL
  bundle from a mounted file). Not wired yet; the NetworkPolicy already assumes port 6378.
* **Kafka security.** Managed Kafka requires SASL/OAUTHBEARER with Google credentials; client properties per
  overlay.

## 9. First deploy / runbook

1. Terraform (out of scope here) creates: Cloud SQL (PG16, private IP, `max_connections=400`), Memorystore,
   Managed Kafka, Artifact Registry, Secret Manager secrets, service accounts with Workload Identity bindings, the
   Cloud Armor policy, the certificate map, and the WORM log bucket with a sink for logger `railops.audit.anchor`.
2. On Cloud SQL, as admin, run the role layout from `deploy/compose/postgres/10-roles.sh` (owner + app per service).
3. `kustomize build deploy/k8s/overlays/staging | kubectl apply -f -`, then wait for rollout.
4. Run `scripts/deploy_smoke.py --bootstrap-admin` **once** (enrols the first admin's MFA). Then delete the
   bootstrap secret from Secret Manager.
5. **Rollback:** `kubectl rollout undo`. Migrations are forward-only, expand/contract: a release never drops what
   the previous release reads.

## Hosting on Render (demo or small pilot)

Step-by-step guide, in plain words: [deploy/render/README.md](../deploy/render/README.md) (Render + Neon + Render Key Value + Confluent Cloud; no Kubernetes).
