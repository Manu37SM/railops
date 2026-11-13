# Move RailLens accounts into the RailOps login (single login, step 4C)

**Who:** a RailOps admin with a nationwide grant (`ADMIN:GLOBAL:*`), signed in with an authenticator code.
**Result:** every RailLens account gets a RailOps account with the **same email and the same password**, and the two are linked. Users notice nothing yet. Their sign-in only changes at step 4D.

## Before you start (once)

1. **RailOps is live** and you can sign in to its staff app as the admin.
2. **RailLens accepts RailOps sign-ins.** On the RailLens backend (Render → `raillens-train-db` → Environment):
   - `RAILLENS_RAILOPS_JWKS_URL` = RailOps identity URL + `/.well-known/jwks.json`
   - `RAILLENS_RAILOPS_ISSUER` = the RailOps identity URL, exactly as RailOps uses it (its `JWT_ISSUER`)
   - The RailLens backend must include Phase 4C (`security/phase4c-account-move` merged).
3. **RailOps knows where RailLens is:** `RAILOPS_RAILLENS_BASE_URL` on the RailOps identity service. `print-env.sh` sets it from `RAILLENS_API_URL` in `render.env`.

## Do it

1. RailOps staff app → **Run operations** → **Move RailLens accounts** → **Move accounts…** → **Yes, move them**.
2. Wait. The first try can take a minute if RailLens was asleep.
3. Read the summary:

| Line | Meaning |
|---|---|
| Moved now | New RailOps accounts created this time |
| Moved before | Already moved earlier (their link was re-sent, which is harmless) |
| Email already here | Someone already had a RailOps account with that email. **Not merged**, see below |
| Couldn't move | The RailLens account has no valid password or email (rare). Their RailLens ids are listed |
| RailLens admins … | Moved as normal passengers. If they need staff rights in RailOps, give them roles yourself (with MFA) |

You can press the button again at any time. It only ever adds what's missing.

## "Email already here": what to do

Two separate accounts exist for one email: one in RailLens, one in RailOps. **Nothing is merged automatically.** Neither app checks that an email really belongs to the person, so merging could hand one person's account to someone else. Until it's sorted out, that person keeps signing in to RailLens with their RailLens password. Sorting it out needs a check that both accounts belong to the same person (for example, they sign in to both while you watch), then a reviewed one-off fix. Ask before doing this.

## Checks afterwards

- RailOps audit log: one `IMPORT_RAILLENS_ACCOUNT` entry per moved account, plus one `RAILLENS_ACCOUNT_MOVE` summary.
- A moved person can sign in to the RailOps staff/passenger site with their RailLens email and password. Their password is quietly upgraded to RailOps' stronger hashing at that first sign-in.
