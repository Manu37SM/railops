# railops-web: Angular frontend

Angular 22 (standalone components, signals, zoneless change detection, Vitest), no UI component library.

## Structure
```
src/app/
  core/
    api/        models.ts (mirrors OpenAPI), reservation-api.ts, api-error.ts (code → message),
                idempotency.ts, server-clock.ts
    auth/       auth.service.ts (session signals), auth.interceptor.ts, auth.guards.ts
    payment-redirect.ts   allow-listed checkout redirect
  shared/       problem-alert, status-badge, hold-countdown
  features/     auth (login + MFA enrolment, register), search, booking (form, detail, list), staff
```
Every feature route is lazy-loaded (1–4 kB gzipped each); the initial bundle is ~82 kB gzipped.

## Decisions
| Decision | Why |
|---|---|
| Access token in a signal only; refresh token in an HttpOnly cookie | No token is ever readable by injected script. A reload restores the session via `provideAppInitializer` + `/auth/refresh`. |
| Single-flight refresh in the interceptor | N concurrent 401s cause one refresh, not N racing cookie rotations. Retry once, then log out. |
| Bearer token only on `/api/` URLs | A token sent to a third-party URL is a token given away. |
| Idempotency key bound to a fingerprint of the request body | A retry of the same request reuses the key (server replays, no double booking); an edited request gets a new key (no `IDEM_KEY_REUSED`). |
| Countdown uses the server clock (HTTP `Date` offset) | A phone with a wrong clock would otherwise tell passengers their hold expired while the seats are still held. |
| Checkout redirect allow-list (https + configured hosts) | Defence in depth against a compromised payment response sending passengers to a look-alike page. |
| `returnUrl` must be an in-app path | Prevents open redirects through the login page. |
| Errors mapped by stable `code`, server `detail` shown only for codes where it is the useful part | Stack traces and internal wording never reach the UI. |
| Guards are UX only | Every rule is enforced again by the API. |
| Same-origin via dev proxy (`proxy.conf.json`) / gateway in prod | Lets the SameSite=Strict refresh cookie work and removes CORS from the hot path. |

## Accessibility
Labels on every control (checked in the browser test), `role="alert"` only for real errors, polite live region
for the countdown (announces at minute boundaries and the last 30 s), colour + text in status badges,
visible focus rings, 44 px touch targets, skip link, `prefers-reduced-motion`.

## Known gaps
- **i18n:** strings are English-only; Phase 1 requires Indian languages. Next step: `$localize` extraction + Hindi/Marathi/Tamil catalogs.
- **Station search** waits for catalog-service; the UI takes run id and stop numbers for now.
- **No QR code** for MFA enrolment (manual key entry only).
- Automated accessibility audit (axe) not yet in CI; only label coverage is checked.

## Running locally
```
cd railops-web && npm ci && npx ng serve        # http://localhost:4200, proxies to :8081/:8082
npx ng test --watch=false                       # 18 unit tests (Vitest)
python3 -I ../scripts/ui_e2e.py /tmp/shots      # 21 browser checks against the running stack
```
