"""Post-deploy smoke test, run against a deployed stack through its PUBLIC origin (as a browser would), plus a few
probes that only an operator can make. Meant for compose, staging and production canaries.

  python3 -I scripts/deploy_smoke.py --web http://localhost:8088 --gateway http://localhost:8080 \
          --payment-internal http://payment-service:8080 --secrets deploy/secrets [--db-url ...]

Checks: security headers and CSP on the SPA; zero CSP violations while a real browser walks search -> register
-> booking; API happy path (register, login, availability, booking, payment via the simulated PSP, confirmation,
cancellation with refund); edge blocking; client-IP spoofing; the bootstrap admin's MFA enrolment with the
deployed signing key.
"""
import argparse, base64, hashlib, hmac, json, pathlib, re, struct, subprocess, sys, time, urllib.error, urllib.request, uuid

ap = argparse.ArgumentParser()
ap.add_argument("--web", required=True)
ap.add_argument("--gateway", required=True)
ap.add_argument("--payment-internal", required=True, help="sim PSP checkout is internal (a real PSP is external)")
ap.add_argument("--secrets", required=True)
ap.add_argument("--psql", default=None, help="psql connection for the reservation owner (fixture load + audit read)")
ap.add_argument("--identity-psql", default=None)
ap.add_argument("--bootstrap-admin", action="store_true",
                help="first deploy only: enrol the bootstrap admin's MFA (never on a live environment's canary)")
args = ap.parse_args()
SECRETS = pathlib.Path(args.secrets)
results = []


def check(name, cond, detail=""):
    results.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"   -> {detail}"))


def call(method, url, body=None, token=None, headers=None, full=False):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw, st, hd = r.read(), r.status, r.headers
    except urllib.error.HTTPError as e:
        raw, st, hd = e.read(), e.code, e.headers
    parsed = json.loads(raw) if raw[:1] in (b"{", b"[") else raw.decode(errors="replace")
    return (st, parsed, hd) if full else (st, parsed)


def psql(conn, q):
    return subprocess.run(["psql", conn, "-tAc", q], capture_output=True, text=True).stdout.strip()


def totp(secret):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    h = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


WEB, GW, PAY = args.web.rstrip("/"), args.gateway.rstrip("/"), args.payment_internal.rstrip("/")

# ------------------------------------------------------------------------------------------- 0. fixture (test only)
if args.psql and psql(args.psql, "SELECT count(*) FROM reservation.train_run") == "0":
    seed = (pathlib.Path(__file__).parent / "seed.sql").read_text()
    subprocess.run(["psql", args.psql, "-q"], input=seed, text=True, check=True)
    print("loaded scripts/seed.sql fixture (a train run) as the schema owner")

# ------------------------------------------------------------------------------------------- 1. SPA + headers
st, body, hd = call("GET", WEB + "/", full=True)
csp = hd.get("Content-Security-Policy", "")
check("SPA served", st == 200 and "<app-root" in body, st)
check("CSP: scripts only from self, no inline, no framing",
      "script-src 'self'" in csp and "unsafe-inline' ;" not in csp.split("script-src")[1].split(";")[0]
      and "frame-ancestors 'none'" in csp and "object-src 'none'" in csp, csp)
check("index.html never cached (a deploy is picked up on next load)", hd.get("Cache-Control") == "no-store", hd.get("Cache-Control"))
check("no inline <script> in the shipped index.html", not re.search(r"<script(?![^>]*\bsrc=)[^>]*>", body), body[:300])
for h in ["X-Content-Type-Options", "Referrer-Policy", "Strict-Transport-Security", "Permissions-Policy"]:
    check(f"header {h}", hd.get(h), dict(hd))

# ------------------------------------------------------------------------------------------- 2. edge blocking
for path in ["/api/v1/../internal/v1/bookings/2400000018/contact", "/internal/v1/bookings/2400000018/contact",
             "/actuator/env", "/actuator/gateway/routes", "/oauth2/token", "/sim-gateway/orders/x/pay"]:
    check(f"gateway hides {path}", call("GET", GW + path)[0] == 404)

# ------------------------------------------------------------------------------------------- 3. API happy path
email = f"smoke.{uuid.uuid4().hex[:8]}@example.com"
st, _ = call("POST", WEB + "/api/v1/auth/register", {"email": email, "password": "smoke-test-password-1",
                                                      "phone": "97%08d" % (uuid.uuid4().int % 10**8)})
check("register through the web origin", st in (200, 201), st)
st, login, hd = call("POST", WEB + "/api/v1/auth/login", {"email": email, "password": "smoke-test-password-1"},
                     headers={"X-Requested-With": "railops-web"}, full=True)
tok = login.get("accessToken") if isinstance(login, dict) else None
cookie = hd.get("Set-Cookie", "")
check("login returns an access token", tok, login)
check("refresh cookie is HttpOnly + SameSite=Strict + Secure", all(x in cookie for x in ("HttpOnly", "SameSite=Strict", "Secure")), cookie)

st, av = call("GET", WEB + "/api/v1/runs/1/availability?from=0&to=4&class=3A")
check("availability", st == 200 and av.get("pools"), (st, av))
st, b = call("POST", WEB + "/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A",
             "quota": "GN", "contactPhone": "9820012345", "passengers": [{"name": "Smoke Tester", "age": 30, "gender": "F"}]},
             tok, {"Idempotency-Key": "smoke-" + uuid.uuid4().hex})
check("booking created", st == 201, (st, b))
pnr = b.get("pnr")
st, intent = call("POST", f"{WEB}/api/v1/bookings/{pnr}/payment-intent", token=tok)
check("payment intent (reservation -> payment, service token, JSON)", st == 201 and intent.get("checkoutUrl"), (st, intent))
order = intent["checkoutUrl"].rsplit("/", 1)[1]
call("POST", f"{PAY}/sim-gateway/orders/{order}/pay")
for _ in range(40):
    s = call("GET", f"{WEB}/api/v1/bookings/{pnr}", token=tok)[1]
    if s.get("status") == "CONFIRMED":
        break
    time.sleep(0.5)
check("signed webhook -> PaymentCaptured over Kafka -> CONFIRMED", s.get("status") == "CONFIRMED", s)
st, c = call("POST", f"{WEB}/api/v1/bookings/{pnr}/cancellations", {}, tok, {"Idempotency-Key": "smoke-c-" + uuid.uuid4().hex})
check("cancellation computes a refund", st == 200 and float(c.get("refundAmount", 0)) > 0, (st, c))

# ------------------------------------------------------------------------------------------- 4. client IP spoofing
spoof = "6.6.6.6"
call("POST", WEB + "/api/v1/auth/login", {"email": email, "password": "wrong-password-xyz"},
     headers={"X-Forwarded-For": spoof, "Forwarded": f"for={spoof}"})
if args.identity_psql:
    ip = psql(args.identity_psql, "SELECT ip FROM identity.audit_log WHERE action='LOGIN_FAILED' ORDER BY id DESC LIMIT 1")
    check("audit log records the real client IP, not a client-supplied header", ip and spoof not in ip, ip)

# ------------------------------------------------------------------------------------------- 5. admin bootstrap + keys
admin_pw = (SECRETS / "bootstrap_admin_password").read_text().strip()
st, r = (0, {}) if not args.bootstrap_admin else call("POST", WEB + "/api/v1/auth/login", {"email": "root.admin@railops.local", "password": admin_pw})
if st == 200 and r.get("enrolmentToken"):
    et = r["enrolmentToken"]
    secret = call("POST", WEB + "/api/v1/auth/mfa/enrol", token=et)[1]["secretBase32"]
    call("POST", WEB + "/api/v1/auth/mfa/confirm", {"code": totp(secret)}, et)
    time.sleep(31 - int(time.time()) % 30)
    st, r = call("POST", WEB + "/api/v1/auth/login", {"email": "root.admin@railops.local", "password": admin_pw, "otp": totp(secret)})
    check("bootstrap admin: password from secret, MFA enrolment, MFA login", st == 200 and r.get("accessToken"), (st, r))
elif args.bootstrap_admin:
    check("bootstrap admin requires MFA enrolment on first login", False, (st, r))
jwks = call("GET", WEB + "/.well-known/jwks.json")[1]
pem = (SECRETS / "jwt_private_key.pem").read_text()
pub = subprocess.run(["openssl", "pkey", "-pubout"], input=pem, text=True, capture_output=True).stdout
mod = subprocess.run(["openssl", "rsa", "-pubin", "-modulus", "-noout"], input=pub, text=True, capture_output=True).stdout.split("=")[1].strip()
n = base64.urlsafe_b64encode(bytes.fromhex(mod)).rstrip(b"=").decode()
check("JWKS publishes the key from the mounted secret (not an ephemeral one)", any(k.get("n") == n for k in jwks.get("keys", [])),
      [k.get("kid") for k in jwks.get("keys", [])])

# ------------------------------------------------------------------------------------------- 6. browser + CSP
try:
    from playwright.sync_api import sync_playwright, expect
    with sync_playwright() as p:
        chrome = "/usr/bin/google-chrome"
        browser = p.chromium.launch(executable_path=chrome if pathlib.Path(chrome).exists() else None, args=["--no-sandbox"])
        page = browser.new_page()
        violations, errors = [], []
        page.on("console", lambda m: (violations if "Content Security Policy" in m.text else errors).append(m.text)
                if m.type in ("error", "warning") else None)
        page.add_init_script("document.addEventListener('securitypolicyviolation', e => console.error("
                             "'Content Security Policy violation: ' + e.violatedDirective + ' ' + e.blockedURI))")
        page.goto(WEB + "/search")
        page.get_by_label("Class").select_option("3A")
        page.get_by_role("button", name="Search").click()
        expect(page.get_by_role("row").filter(has_text="GN")).to_contain_text(re.compile(r"AVAILABLE-\d+|RAC-\d+|WL-\d+"))
        page.goto(WEB + "/register")
        page.get_by_label("Email").fill(f"ui.{uuid.uuid4().hex[:8]}@example.com")
        page.get_by_label("Mobile number").fill("96" + str(uuid.uuid4().int)[:8])
        page.get_by_label("Password").fill("browser-smoke-password")
        page.get_by_role("button", name="Create account").click()
        try:
            page.wait_for_url("**/search", timeout=15000)
        except Exception:
            print("browser stuck on", page.url, "| alert:", page.locator("[role=alert]").all_inner_texts(), "| console:", errors[-3:])
        styled = page.evaluate("[...document.styleSheets].reduce((n, s) => n + (s.href ? s.cssRules.length : 0), 0)")
        check("browser: search -> register -> logged in, through the real CSP",
              page.get_by_role("button", name="Log out").is_visible(), page.url)
        check("browser: external stylesheet loaded and parsed (CSP did not block styles)", styled > 10, styled)
        check("browser: zero CSP violations", not violations, violations)
        browser.close()
except ImportError:
    print("SKIP browser checks (playwright not installed)")

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
