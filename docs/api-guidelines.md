# RailOps API conventions

Every RailOps service follows these rules. The generated contracts are in `docs/openapi/`
(`reservation-v1.yaml`, `identity-v1.yaml`, OpenAPI 3.1), exported from the running services.

## Versioning
- Major version in the path: `/api/v1/...`. Additive changes (new optional fields, new endpoints) stay in v1.
- Removing or renaming a field, changing its meaning, or tightening validation is a breaking change: new `/api/v2`
  path, both served in parallel, v1 deprecated with a `Deprecation` + `Sunset` header for at least 6 months.
- Clients must ignore unknown response fields. Servers must not require fields that were optional before.

## Authentication
| Caller | Token | Audience | Lifetime |
|---|---|---|---|
| Passenger / agent / staff | `Authorization: Bearer <JWT>` from `POST /api/v1/auth/login` | `railops-api` | 10 min |
| Browser session renewal | HttpOnly `rt` cookie on `/api/v1/auth`, plus header `X-Requested-With: railops-web` | n/a | 14 days (staff 12 h) |
| Service to service | `client_credentials` at `POST /oauth2/token` | `railops-internal` | 5 min |
| MFA enrolment only | returned by login when enrolment is pending | `railops-mfa` | 5 min |

Tokens carry no personal data. Staff endpoints (`/api/v1/staff/**`, `/api/v1/admin/**`) require `amr` to include `otp`.

## Authorization
Roles come from the token. Staff roles are scoped (`ROLE:SCOPE_TYPE:VALUE`, e.g. `CONTROLLER:DIVISION:BRC`) and checked
against the resource. A run is in scope when any station it calls at falls inside the grant. Out-of-scope requests
get **403**. Other users' resources get **404**, never 403, so a resource's existence isn't revealed.

## Errors
Every error is an RFC 9457 `application/problem+json` body with a stable machine-readable `code`:
```json
{ "type": "about:blank", "title": "RES_REGRET", "status": 409, "detail": "waitlist is full for this class and quota",
  "instance": "/api/v1/bookings", "code": "RES_REGRET" }
```
Clients switch on `code`, never on `detail` (wording may change). 5xx bodies never include exception messages.

| Status | Meaning in RailOps |
|---|---|
| 400 | Malformed request / validation (`VALIDATION_FAILED`, with `errors[]` per field where safe) |
| 401 | Missing, expired or invalid token; all login failures (`AUTH_FAILED`, deliberately uninformative) |
| 403 | Authenticated but not permitted (role, scope or MFA) |
| 404 | Not found **or not yours** |
| 409 | Valid request, conflicting state (`RES_REGRET`, `RES_CANCELLATION_CLOSED`, `RES_CHART_NOT_ALLOWED`, ...) |
| 410 | Gone: the seat hold expired (`RES_HOLD_EXPIRED`) |
| 422 | Business rule violation (`RES_QUOTA_INELIGIBLE`, `RES_INVALID_CANCELLATION`, `IDEM_KEY_REUSED`, ...) |
| 503 | Dependency unavailable (`PAYMENT_UNAVAILABLE`, `DEPENDENCY_UNAVAILABLE`); retry with backoff |

## Idempotency
Mutations that move money or inventory (`POST /bookings`, `POST /bookings/{pnr}/cancellations`) require
`Idempotency-Key` (16–64 chars of `[A-Za-z0-9-]`, a fresh UUID per logical attempt):
- Same key + same body → the original response is replayed (same PNR, same cancellation id, no second refund).
- Same key + different body → `422 IDEM_KEY_REUSED`.
- Keys are scoped per user and kept for 24 h.
`POST /bookings/{pnr}/payment-intent` is naturally idempotent (one live payment per PNR) and needs no key.

## Pagination
List endpoints use keyset pagination: `?limit=` (max 50) and an opaque `cursor` taken from `nextCursor`. No `nextCursor`
means the last page. Cursors are not stable across API versions; don't store them.

## Time, money, identifiers
- Instants are ISO-8601 UTC (`2026-11-20T10:30:00Z`); dates are `YYYY-MM-DD` in the train's origin timezone (`runDate`).
- Money is a JSON number with 2 decimals, always INR, computed server-side. No endpoint accepts a price from a client.
- PNRs are 10 digits with a Luhn check digit; malformed PNRs are rejected before any lookup.

## Caching
Only `GET /api/v1/runs/{runId}/availability` is publicly cacheable (`max-age=5`). It is advisory: the booking call
re-checks under database locks. Everything authenticated is `no-store`.

## Rate limits (enforced at the gateway, Phase 10)
`429` with `Retry-After`. Login and registration are limited per IP and per account; booking is limited per user,
with a waiting room at Tatkal opening.
