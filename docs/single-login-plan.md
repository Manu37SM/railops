# One login for RailLens and RailOps: plan

**Status (13 Nov 2026):** steps 4A, 4B and 4C done. Step 4D done for RailOps, the RailLens backend, the website and the Android app (each behind a setting). Phase 5A (booking on the website) and 5B (booking in the Android app) done, each behind its own switch.

**Decided earlier:** RailOps' identity service becomes the one login for both apps. Passengers book inside the RailLens website and Android app; the RailOps web app stays for staff.

## Why this comes before booking

Booking happens in RailOps, so a RailLens user needs a RailOps session to book. Three things stopped RailOps' login from working for RailLens as it was:

1. **The RailOps refresh token was a browser cookie on RailOps' own domain.** On the RailLens website that cookie belongs to another site, and browsers block it. The Android app can't use it at all.
2. **RailOps tokens didn't say who the person is in a way RailLens can use.** They carried a RailOps user id but no email, and RailLens accounts are found by username or email.
3. **The two apps have separate accounts.** The same person may exist in both, with different passwords.

## The steps

| Step | What | Breaking? | Status |
|---|---|---|---|
| **4A** | RailOps identity: **app sessions** (`/api/v1/auth/app/login`, `/refresh`, `/logout`) return the refresh token in the response body, for the Android app and the RailLens website's server. Access tokens now carry the account's **email**. The browser endpoints are unchanged, and the gateway rate-limits the app login like the normal one. | No | ✅ done |
| **4B** | RailLens backend also **accepts RailOps access tokens**, checked against RailOps' public keys (issuer, audience `railops-api`, expiry), alongside its own. It reaches an existing account **only through its stored RailOps id** (set in 4C). A brand-new email gets a new profile; an email that belongs to an unlinked RailLens account is refused. Off until `RAILLENS_RAILOPS_JWKS_URL` is set. | No | ✅ done |
| **4C** | **Move RailLens accounts into RailOps** with a one-off, repeatable import, and store each new RailOps id on the RailLens account (that is the link 4B trusts). RailLens stores passwords as bcrypt, which RailOps can already check (and upgrades to Argon2 at the next login), so **everyone keeps their password**. An email that already has a RailOps account is **reported, not merged**. RailLens admins are moved as passengers and **listed**, so a RailOps admin grants staff roles deliberately (never automatically). Started from the RailOps staff app: *Run operations → Move RailLens accounts*. Runbook: `docs/runbooks/move-raillens-accounts.md`. | No (users notice nothing yet) | ✅ done |
| **4D** | **Switch the RailLens login.** RailOps identity gains `POST /api/v1/auth/password` and `POST /api/v1/auth/erase` (user token + password; wrong guesses count toward the lockout; erase is passengers only and keeps bookings/payments as financial records). The gateway can take the visitor IP from the RailLens website server when it presents `RAILOPS_EDGE_PROXY_SECRET`. The RailLens website's server-side sign-in routes call RailOps' app endpoints when `NEXT_PUBLIC_RAILLENS_LOGIN_PROVIDER=railops` (default stays `raillens`), keep the refresh token in its own HttpOnly cookie, and get the profile from RailLens' `/auth/me`. Registration asks for a phone number. The RailLens backend forwards password change and account closing for linked profiles to RailOps and clears its own password copy. The Android app does the same from `src/config/login.ts` (`LOGIN_PROVIDER`), tokens in the Keychain, sessions from the other login dropped after an update. | **Yes**: the one switch-over, by one website setting. Order, checks and rollback: `DEPLOYMENT.md` Section 9b. | ✅ done (switch not flipped yet) |
| **Phase 5A** | **Booking on the RailLens website**: train page → date and stations → seats per class → passengers → hold → pay (simulated) → ticket; my bookings; cancel with a refund preview. RailOps gains a public run lookup (`GET /api/v1/runs?trainNo&date`, `/runs/{id}`) and a demo-only `POST /bookings/{pnr}/simulated-payment` (owner only; on only with `RAILOPS_PAYMENTS_SIMULATED=true`), because the simulated checkout couldn't be completed outside local tests. | No (off until `NEXT_PUBLIC_RAILOPS_API_URL` is set) | ✅ done |
| **Phase 5B** | Booking in the Android app, same API (`BOOKING_ENABLED` in `src/config/login.ts`). | No (off until switched on) | ✅ done |
| **Phase 6** | Razorpay **test mode** gateway (Payment Links, signed webhooks, refunds deduplicated by receipt; live keys refused) as an alternative to the simulated one (`PAYMENT_GATEWAY`); coach layouts for all 8 classes and standard coach sets; public live seat map (`GET /runs/{id}/seat-map`); staff change-password page. Dodo Payments was considered and not used: its policy excludes travel services. | No | ✅ done |

## Decisions (and why)

- **Separate app endpoints instead of changing the cookie ones.** The RailOps web app keeps its proven cookie flow untouched. The app flow differs only in where the refresh token travels, and needs no CSRF header because no cookie is involved.
- **The link between the two apps is the RailOps user id, not the email.** Neither app verifies email addresses, so "same email" doesn't prove "same person": someone could register your email at RailOps before your account moves over. The move (4C) creates the RailOps account *from* the RailLens account, which does prove it, and records the id. Email is only used to stop duplicates (an unlinked email that RailLens already has is refused). Usernames exist only in RailLens; they stay as display names.
- **Keep passwords.** Forcing everyone to reset would lose users. RailOps already supports legacy bcrypt hashes.
- **Mobile number:** new sign-ups give one (RailOps sends booking SMS to it). Moved accounts have none until the person adds it. Booking always asks for a contact number anyway.
- **RailLens' Phase 2A admin MFA and 2B cookie work are not wasted.** 2B's server-side sign-in routes are exactly where 4D plugs in. 2A's admin accounts move over in 4C (as passengers, listed for a RailOps admin to grant roles), and RailOps already enforces MFA for staff.
- **Why the move is pulled by RailOps rather than written straight into its database:** it goes through RailOps' own account code and audit log (a hash chain that direct inserts would break), needs no database access from your computer, and the RailLens export it reads is only open to an admin who signed in with a one-time code; the old shared admin key is refused there because the export contains password hashes.

## What could go wrong, and the guard for it

| Risk | Guard |
|---|---|
| Someone forges a token for RailLens | 4B only accepts RS256 tokens signed by RailOps' published key, with the right issuer and audience, not expired. RailLens' own HS256 secret is never shared. |
| Someone takes over an account by registering its email at RailOps | 4B never links by email: only by the stored RailOps id. A RailOps login whose email belongs to an unlinked RailLens account is refused (tested, case-insensitively). 4C reports email collisions with pre-existing RailOps accounts instead of merging them. |
| The switch-over (4D) locks people out | 4C runs first and is checked: every RailLens account must exist in RailOps before 4D is deployed. 4D can be rolled back to RailLens' own login, which keeps working until it is finally removed. |
| Email inside access tokens | Access tokens live 10 minutes and aren't logged; RailLens already shows the email to its owner. |
