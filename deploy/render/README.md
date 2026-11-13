# Put RailOps live on Render (step by step)

> This guide covers **RailOps only**. For what to deploy first when RailLens is involved, and for the overall release tracker, see [`DEPLOYMENT.md`](../../DEPLOYMENT.md) at the root of this repo.

This guide puts the whole RailOps system on the internet using:

| Piece | Where it runs | What it is, in one line |
|---|---|---|
| 5 Java services | **Render** | identity (login), reservation (seats), payment, notification (SMS), api-gateway (the front door) |
| Web app (Angular) | **Render** static site | what people open in the browser |
| Database (PostgreSQL) | **Neon** | where bookings, users and payments are stored |
| Cache (Redis) | **Render Key Value**, or **Redis Cloud** free if your free Key Value is taken | short-lived data: rate limits, availability numbers |
| Message bus (Kafka) | **Confluent Cloud** | the services' "post office" (explained below) |

It takes about **1.5 to 2 hours** the first time. You don't need to know Kafka or Kubernetes.

> **Read this first: what "live" means today**
> - **Payments are FAKE.** RailOps only has a *simulated* payment gateway. Nobody can be charged real money, and you must not connect real payments until a real payment-provider adapter is built and reviewed.
> - The waitlist-prediction model (`docs/ml/model-card.md`) stays **off**: it was trained on synthetic data.
> - This setup is a **public demo**. For real passengers you'd need the "Reliable" mode below, backups, a real SMS provider and a security review.

---

## Two words you were unsure about

**Kafka** is a post office between the services. When reservation confirms a booking, it doesn't call notification directly. It drops a letter ("BookingConfirmed") in the post office, and notification picks it up whenever it's ready. If notification is down for an hour, the letters wait instead of getting lost. You won't run Kafka yourself: **Confluent Cloud** runs it for you. You create a cluster in their dashboard and paste one address and one key into RailOps.

**Kubernetes** is a system for running many containers on your own servers. **You don't need it on Render**, because Render runs the containers for you. The `deploy/k8s/` folder is for a possible later move to Google Cloud (GKE). Ignore it for now.

---

## Step 0: choose a mode

| | **Demo mode** | **Reliable mode** |
|---|---|---|
| Render plan | everything on **Free** | gateway on **Starter** (public); the 4 other services as **Private Services** on Starter |
| Render cost | $0 | about **$35/month** (5 × $7) |
| Neon | Free | probably the paid **Launch** plan (pay as you go). Always-on services keep the database awake and use up the free compute hours |
| Sleeps when idle? | **Yes.** Each Free service sleeps after 15 minutes without visitors and takes about a minute to wake | No |
| Background jobs (expire unpaid holds, send queued events, prepare charts) | **Pause while asleep**, then catch up when woken | Run all the time |
| Internal service-to-service calls | Go over the public internet (Free services can't receive private-network traffic). Still protected by service tokens | Stay on Render's private network |
| Good for | showing the project, interviews, testing | a pilot with real users (fake payments still!) |

**Start with Demo mode.** Everything below is the same for both modes. The few differences are marked **[Reliable]**. Moving to Reliable later is a change of plan, not a rebuild.

Also note Render's free allowance: **750 free instance hours per month for the whole account**. Sleeping services don't use hours, so a quiet demo stays free. Five services awake all month would not.

---

## Step 1: what you need on your computer

- A **GitHub** account, and **Git**
- **Git Bash** (comes with Git for Windows) or **WSL** to run the `.sh` scripts
- **OpenSSL** (included in Git Bash)
- **psql**, the PostgreSQL command-line client. On Windows, install "PostgreSQL" and tick only *Command Line Tools*; or in WSL run `sudo apt install postgresql-client`
- **Python 3** (the setup script uses it to read your Neon address)

Check:
```bash
git --version && openssl version && psql --version && python3 --version
```

## Step 2: put RailOps on GitHub (private)

Render deploys from a Git repository. In the `railops` folder:

```bash
git init
git add .
git status            # CHECK: nothing under deploy/secrets/ or deploy/render/render.env is listed
git commit -m "RailOps"
```

On github.com, create a **private** repository named `railops` (no README, no .gitignore), then:

```bash
git remote add origin https://github.com/<your-user>/railops.git
git branch -M main
git push -u origin main
```

> `.gitignore` already keeps `deploy/secrets/`, `deploy/render/render.env` and `deploy/render/out/` out of Git. Those hold passwords: never commit them.

## Step 3: make the secrets (once)

```bash
scripts/gen-secrets.sh
```

This creates `deploy/secrets/`: database passwords, encryption keys, the JWT signing key, the service-client secrets and (since the RailLens single login, Phase 4D) `raillens_proxy_secret`, which becomes the gateway's `RAILOPS_EDGE_PROXY_SECRET` and the RailLens website's `RAILOPS_PROXY_SECRET`. Running it again only adds files that are missing. **Keep this folder.** If you lose it you'll have to reset every password. Back it up somewhere private, such as a password manager, not Git.

## Step 4: pick one region for everything

Put every piece in the same part of the world, or each request pays for the distance. From India:

| Service | Region |
|---|---|
| Render | **Singapore** |
| Neon | **AWS Asia Pacific (Singapore)** |
| Confluent | **AWS**, a Singapore or Mumbai region |

Use the same choice for every Render service and the Key Value instance.

## Step 5: database on Neon

1. Sign up at **neon.com** and create a project. Pick the region from Step 4 and PostgreSQL **16 or newer**.
2. Open **Connect**. Make sure **Connection pooling is OFF**, then copy the connection string. The host must **not** contain `-pooler`.
   - Why: RailOps uses database features (session locks, `SET`) that the pooled connection doesn't support. The setup script refuses a pooled string.
3. Run the one-time setup from the `railops` folder (paste your string between the quotes):
   ```bash
   NEON_URL='postgresql://neondb_owner:xxxx@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require' \
     deploy/render/neon-setup.sh
   ```
   You should see `Done. Schemas and roles created`.
   - A NOTICE saying *not allowed to grant pg_read_all_data* is normal on Neon.
   - The script makes one schema per service, with two logins each: an *owner* that changes the tables, and an *app* login that can only read and write rows. A bug in one service can't touch another service's data.
4. Note the **host** part of the string (`ep-xxxx....neon.tech`) and the database name (usually `neondb`).

## Step 6: Kafka on Confluent Cloud

1. Sign up at **confluent.io/confluent-cloud**. It may ask for a card; new accounts usually get free trial credit.
2. Create the cluster. **The first screen offers a *Standard* cluster ($0.75/hour, about $550/month). Don't click Continue there.**
   - Click **Explore other cluster types and pricing** → pick **Basic**. Basic has no base price: the first eCKU is free.
   - Cloud **AWS**, region **Singapore (ap-southeast-1)**, or **Mumbai (ap-south-1)**. Name it `railops`. Single zone is fine.
   - Before the final **Launch**, check the summary says **Basic**.
3. Open the cluster:
   - **Cluster settings**: copy the **Bootstrap server** (`pkc-xxxxx....confluent.cloud:9092`) into `KAFKA_BOOTSTRAP`.
   - **API Keys → Add key → My account** (global access): copy the **key** and **secret** into `KAFKA_API_KEY` and `KAFKA_API_SECRET`. The secret is shown once; put it in your password manager too.
4. Don't create topics: the services create the 12 they need on first start.
5. Keep `KAFKA_MAX_PARTITIONS=2` in `render.env`. Basic includes 30 partitions per eCKU. At 2 partitions each, the 12 topics use 24 and stay in the free eCKU. RailOps' full counts (120 partitions) would bill about $300/month in extra eCKUs. **Kafka can't shrink a topic later**, so this must be set before the services first start.
6. Optional but wise: **Billing → set a budget alert** (for example $5).

> Why not Aiven's free Kafka? Its free plan allows 5 topics, and RailOps uses 12: 5 event topics, plus 7 "dead-letter" topics where broken messages are parked for a human. Upstash Kafka no longer exists.

## Step 7: Redis

Render allows **only one Free Key Value per workspace**. Check **dashboard.render.com → Key Value** first.

**A. You have no Free Key Value yet:** use Render.
1. **New → Key Value**. Name it `railops-cache`, region from Step 4, plan **Free**.
2. Open it → **Connect → Internal**. The URL looks like `redis://red-xxxxxxxx:6379`. In `render.env` set `REDIS_HOST=red-xxxxxxxx`, `REDIS_PORT=6379` and leave `REDIS_PASSWORD` empty.

**B. Your Free Key Value is already used by another project:** use **Redis Cloud** (free, no card).
1. Sign up at **redis.io/try-free** → create a **Free** database (30 MB). Pick **AWS**, region **Singapore (ap-southeast-1)**, or **Mumbai (ap-south-1)** if Singapore isn't offered.
2. Open the database:
   - **Public endpoint** looks like `redis-12345.c1.ap-southeast-1-1.ec2.cloud.redislabs.com:12345`. The part before `:` is `REDIS_HOST`, the number after it is `REDIS_PORT`.
   - **Security → Default user password**: copy it into `REDIS_PASSWORD`. Keep `REDIS_USERNAME=default` and `REDIS_SSL=false`.
3. Limits that matter: 30 connections and about 100 operations a second. RailOps uses 2 connections and a handful of operations per request, which is plenty for a demo.

> Why not Upstash? Its free plan counts commands (500K/month), and reservation's waiting-room check runs once a second while it's awake, which can use that up. Redis Cloud's free plan has no monthly command cap.
>
> **Risk:** Redis Cloud's free database talks **without TLS**, so the password and the cached data cross the internet unencrypted. RailOps keeps short-lived rate-limit counters (keyed by **visitor IP address** or internal user ID), seat counts and waiting-room places (internal user IDs) there: no names, phones, tokens or payments, and everything expires or rebuilds itself. IP addresses still count as personal data. Still, use a password you use nowhere else, and move to a TLS Redis (paid Render Key Value, or Upstash with `REDIS_SSL=true`) before real users.

Either way the data is memory-only and can vanish on a restart. That's fine: it rebuilds itself.

## Step 8: create the five services on Render

For **each** row below: **New → Web Service → connect your GitHub `railops` repo**, then fill in:

| Field | Value |
|---|---|
| Name | from the table |
| Region | Step 4 |
| Branch | `main` |
| Language / Runtime | **Docker** |
| Dockerfile path | `deploy/docker/service.Dockerfile` |
| Docker build context | `.` (the repo root) |
| Instance type | **Free** (Demo). **[Reliable]** gateway: Starter; the others: create as **New → Private Service** on Starter |
| Environment variable (for now) | `SERVICE` = the module from the table. Render passes env vars to the Docker build, which uses this to pick the service |
| Health check path | from the table |

| Name | `SERVICE` | Health check path |
|---|---|---|
| `railops-identity` | `identity-service` | `/actuator/health/readiness` |
| `railops-reservation` | `reservation-service` | `/actuator/health/readiness` |
| `railops-payment` | `payment-service` | `/actuator/health/readiness` |
| `railops-notification` | `notification-service` | `/actuator/health/readiness` |
| `railops-gateway` | `api-gateway` | `/actuator/health/readiness` |

**The first deploy of each service will fail.** That's expected: it has no settings yet. You only need the services to exist so you can copy their addresses.

After creating each one, note its address: the public `https://railops-xxx.onrender.com` (Demo), or for **[Reliable]** private services, **Connect → Internal** (`http://railops-xxx:8080`). If a name is taken, Render adds a suffix; use whatever it shows.

## Step 9: create the web app (static site)

**New → Static Site →** your repo, region from Step 4:

| Field | Value |
|---|---|
| Name | `railops-web` |
| Build command | `cd railops-web && npm ci && npx ng build --configuration production` |
| Publish directory | `railops-web/dist/railops-web/browser` |
| Environment variable | `NODE_VERSION` = `22` |

Then in the site's settings:

**Redirects/Rewrites** (in this order):

| Source | Destination | Action |
|---|---|---|
| `/api/*` | `https://railops-gateway.onrender.com/api/*` (your gateway URL) | **Rewrite** |
| `/*` | `/index.html` | **Rewrite** |

The first rule makes the API look like part of the website. That's required: the login cookie is "same-site only", and the page's security policy only allows calls to its own address. The second rule lets Angular handle page links like `/bookings/123`.

**Headers** (path `/*`), copied from the production nginx config:

| Name | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'; upgrade-insecure-requests` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `no-referrer` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=()` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `Cross-Origin-Opener-Policy` | `same-origin` |

Note its address (`https://railops-web.onrender.com`).

## Step 10: generate every setting with one command

1. Copy the template and fill it in with what you noted in Steps 5 to 9:
   ```bash
   cp deploy/render/render.env.example deploy/render/render.env
   # edit deploy/render/render.env
   ```
2. Generate the settings for all five services:
   ```bash
   deploy/render/print-env.sh all
   ```
   This writes `deploy/render/out/identity.env`, `reservation.env`, `payment.env`, `notification.env` and `gateway.env`. They contain passwords: they stay on your computer and are gitignored.

## Step 11: paste the settings and deploy

For **each** service on Render, open **Environment** and:

1. Click **Add from .env**, paste the whole content of its file from `deploy/render/out/`, and save. Keep the `SERVICE` variable from Step 8.
2. **identity only:** under **Secret Files**, add a file:
   - Filename: `railops.jwt.private-key-pem` (exactly this)
   - Contents: everything in `deploy/secrets/jwt_private_key.pem`

   This is the key that signs every login token. Render mounts it at `/etc/secrets/`, where RailOps reads it.
3. **Manual Deploy → Deploy latest commit.**

**Deploy in this order and wait for "Live" each time:**
1. **identity** (the others fetch its public key at start)
2. **reservation**
3. **payment**
4. **notification**
5. **gateway**

On a Free instance a Java service can take **several minutes** to start, because it gets a small slice of a CPU. In the logs, look for `Successfully applied N migrations` (the tables were created) and then `Started ...Application`.

## Step 12: check it works

1. `https://railops-gateway.onrender.com/actuator/health` shows `{"status":"UP"...}`.
2. Open `https://railops-web.onrender.com`, register an account, and log in.
3. Log in as the admin: the email you set in `render.env` and the password in `deploy/secrets/bootstrap_admin_password`. You'll be asked to set up MFA with an authenticator app.
4. Still as the admin: **Run operations** → **Open a train for booking**. Enter a train RailLens knows (for example its first train), dates from next week, and a few coaches. A fresh database has no trains until you do this. See "Put trains on sale" below.
5. Book a ticket on one of those dates **on the RailLens website** (train page → *Book this train*; needs RailLens' single login switched on and `NEXT_PUBLIC_RAILOPS_API_URL` set, see the shared `DEPLOYMENT.md`), then press **Pay (simulated)**. The booking should turn *Booked* within a few seconds. That's the full chain: reservation → payment (simulated gateway) → webhook → Kafka → reservation, and notification "sends" an SMS into its log.
   - Why not in the RailOps web app: its "Pay now" sends people to a real gateway's checkout page, and there is no real gateway here. Before Phase 5 this step couldn't actually be completed on Render. Passengers book on RailLens; the RailOps web app is for staff.
   - `print-env.sh` sets `RAILOPS_PAYMENTS_SIMULATED=true` on reservation, which allows that demo payment. Remove it if a real payment gateway is ever added.

If something fails, check **Troubleshooting** below, then the service's **Logs** tab on Render.

---

### Put trains on sale (Phase 3B)

RailOps reads train timetables from **RailLens**. In `render.env`, `RAILLENS_API_URL` says where (the default is the live RailLens API). After the first deploy, open trains for booking from the web app: log in as the admin → **Run operations** → **Open a train for booking**. Step by step: [`docs/runbooks/open-trains-for-booking.md`](../../docs/runbooks/open-trains-for-booking.md).

`RAILLENS_WEB_ORIGIN` lets the RailLens website call the RailOps API from the browser (needed once booking moves into RailLens). Leave it empty until then if you prefer.

## What it costs and where the limits are

| Item | Demo mode | Reliable mode |
|---|---|---|
| Render services | $0 (Free; shares 750 hours/month) | ≈ $35/month (5 × Starter at $7) |
| Render static site | $0 | $0 |
| Render Key Value / Redis Cloud | $0 (no persistence) | $0, or a paid plan if you need persistence and TLS |
| Neon | $0 (Free: 1 GB per project; compute sleeps after 5 idle minutes) | likely paid Launch (usage-based). Always-on services keep the compute awake beyond the free hours |
| Confluent Basic | ≈ $0 to a few dollars (first eCKU free; pay per GB moved/stored). Needs `KAFKA_MAX_PARTITIONS=2` | same |

Prices change. Check each provider's pricing page before relying on these numbers.

**Known limits of this setup:**
- **Cold starts (Demo):** the first visit after 15 quiet minutes waits about a minute per sleeping service. A request can time out while a service it depends on is still waking. Wait a minute and retry.
- **Paused background work (Demo):** while reservation sleeps, unpaid holds aren't released on time and charts aren't prepared on schedule. They catch up when it wakes. This is fine for a demo, but not for real passengers.
- **Fake payments only.** Real payments need a payment-provider adapter (Phase 15 "Deferred").
- **Client IP for rate limits:** fixed in Phase 3B. The gateway reads `True-Client-IP` (set by Cloudflare in front of Render), and only when the request came through Render's proxy. `print-env.sh` sets this for you.
- **The static-site rewrite adds latency** to each API call (a few hundred milliseconds is commonly reported). A custom domain pointed straight at the gateway removes it later.
- **Backups:** Neon Free keeps only a short restore window. Before real users, move to a paid plan with a longer history.

## Troubleshooting

| You see | Usually means | Fix |
|---|---|---|
| Build fails: `SERVICE` empty or *module not found* | `SERVICE` variable missing or misspelled | Set it exactly as in Step 8 and redeploy |
| `password authentication failed for user "xxx_owner"` | Wrong database password, or `neon-setup.sh` not run | Re-run Step 5.3 on a fresh Neon project, then Step 10 |
| `prepared statement ... does not exist` / lock errors | You used the **pooled** Neon string | Use the direct string (no `-pooler`) in `render.env`, regenerate, re-paste |
| gateway/reservation: `NOAUTH` or `WRONGPASS` in logs | Redis Cloud password missing or wrong | Set `REDIS_PASSWORD` in `render.env`, regenerate, re-paste gateway and reservation |
| identity: *JWT private key missing* | Secret file not added or misnamed | Step 11.2: filename must be exactly `railops.jwt.private-key-pem` |
| Confluent bill shows extra eCKUs | Topics were created before `KAFKA_MAX_PARTITIONS` was set (120 partitions) | Delete the 12 topics in Confluent, check `RAILOPS_KAFKA_MAX_PARTITIONS=2` on identity, reservation, payment and notification, restart them |
| Kafka: `SaslAuthenticationException` / *Failed to create topics* | Wrong API key, or the key lacks access | Make a new **Global** key in Confluent, update `render.env`, regenerate, re-paste |
| Service restarts with *OutOfMemory* | 512 MB was too small for it | Switch that service to **Standard** (2 GB) |
| Browser: login works but you're logged out on refresh | Rewrite rule missing, so the API isn't same-site | Step 9 rewrite `/api/*` must be first |
| Browser console: *Refused to connect* (CSP) | The app calls the gateway URL directly | Same fix: the `/api/*` rewrite |
| Everything is slow the first time | Free services waking up | Normal in Demo mode |
| Free services suspended late in the month | 750 free hours used up | Fewer always-awake services, or move to Reliable mode |

## Going from Demo to Reliable later

1. Change the gateway's instance type to **Starter**.
2. Recreate identity, reservation, payment and notification as **Private Services** (Starter) with the same settings.
3. In `render.env`, set `IDENTITY_URL`, `RESERVATION_URL` and `PAYMENT_URL` to their **internal** addresses (`http://railops-identity:8080` etc.), regenerate, re-paste, redeploy.
4. Update the static site's `/api/*` rewrite only if the gateway's URL changed.

Your data stays in Neon, so nothing is lost.

## How this guide was checked

On 2026-11-12, `print-env.sh` generated the settings for all five services. Each service was then started from **only** those settings against a PostgreSQL 16 database set up by `neon-setup.sh` with a **non-superuser** owner, the same situation as Neon. Each service ran in a 512 MB memory budget (reservation peaked at about 300 MB). All five started with zero errors, migrated their own schemas as owner roles, and passed a register → login → token-checked API call through the gateway. `/actuator/prometheus` was not publicly reachable on any of them.

**Not tested from here:** the Render, Neon and Confluent dashboards themselves (no accounts), Confluent's SASL login, and the static-site rewrite.
