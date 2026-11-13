"""End-to-end security scenarios against identity-service (:8082) and reservation-service (:8081)."""
import base64
import hashlib
import hmac
import json
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ID = "http://localhost:8082"
RES = "http://localhost:8081"
results = []


def call(method, url, body=None, token=None, headers=None, cookie=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if cookie:
        h["Cookie"] = "rt=" + cookie
    h.update(headers or {})
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {}), r.headers
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else {}, e.headers
        except ValueError:
            return e.code, {"raw": raw[:100]}, e.headers


def rt_cookie(headers):
    for v in headers.get_all("Set-Cookie") or []:
        if v.startswith("rt="):
            return v.split(";")[0][3:]
    return None


def totp(secret_b32, offset=0):
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    step = int(time.time()) // 30 + offset
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def check(name, cond, detail=""):
    results.append((name, cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"   -> {detail}"))


def claims(jwt):
    p = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))


def wait_fresh_step():
    """A TOTP step may be used once per account; wait for the next 30 s window when reusing a secret."""
    time.sleep(31 - (int(time.time()) % 30))


def enrol_staff(email, password):
    s, b, _ = call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": password})
    assert b.get("status") == "MFA_ENROLMENT_REQUIRED", b
    enrol_token = b["enrolmentToken"]
    s, e, _ = call("POST", f"{ID}/api/v1/auth/mfa/enrol", token=enrol_token)
    s2, _, _ = call("POST", f"{ID}/api/v1/auth/mfa/confirm", {"code": totp(e["secretBase32"])}, token=enrol_token)
    return enrol_token, e["secretBase32"], s2


def login_staff(email, password, secret):
    s, b, h = call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": password, "otp": totp(secret)})
    return s, b.get("accessToken"), rt_cookie(h)


ADMIN, ADMIN_PW = "root.admin@railops.local", "Local-Only-Bootstrap-Passw0rd"

# --- 1. Bootstrap admin must enrol MFA before anything else
enrol_tok, admin_secret, s = enrol_staff(ADMIN, ADMIN_PW)
check("bootstrap admin enrols MFA (204)", s == 204, s)
s, _, _ = call("GET", f"{RES}/api/v1/bookings/1234567897", token=enrol_tok)
check("enrolment token rejected by reservation (wrong audience) -> 401", s == 401, s)
s, _, _ = call("GET", f"{ID}/api/v1/admin/audit/verify", token=enrol_tok)
check("enrolment token can't reach admin APIs -> 403", s == 403, s)

wait_fresh_step()
s, admin_tok, _ = login_staff(ADMIN, ADMIN_PW, admin_secret)
check("admin login with password + OTP -> 200", s == 200 and admin_tok, s)
check("admin token has amr [pwd, otp] and no PII", claims(admin_tok)["amr"] == ["pwd", "otp"]
      and "email" not in claims(admin_tok), claims(admin_tok))
s, _, _ = call("POST", f"{ID}/api/v1/auth/login", {"email": ADMIN, "password": ADMIN_PW, "otp": totp(admin_secret)})
check("same OTP replayed in the same 30 s step -> 401", s == 401, s)

# --- 2. Staff provisioning with scoped grants
staff = {}
for name, station in [("brc.sm", "BRC"), ("hwh.sm", "HWH")]:
    email = f"{name}.{uuid.uuid4().hex[:6]}@railops.local"
    s, b, _ = call("POST", f"{ID}/api/v1/admin/staff", {"email": email}, token=admin_tok)
    uid, otp_pw = b["userId"], b["oneTimePassword"]
    s, _, _ = call("POST", f"{ID}/api/v1/admin/users/{uid}/grants",
                   {"role": "STATION_MASTER", "scopeType": "STATION", "scopeValue": station}, token=admin_tok)
    _, secret, _ = enrol_staff(email, otp_pw)
    staff[station] = (email, otp_pw, secret, uid)
check("admin creates two station masters and grants station scopes", len(staff) == 2)
s, b, _ = call("POST", f"{ID}/api/v1/admin/users/{claims(admin_tok)['sub']}/grants",
               {"role": "AUDITOR", "scopeType": "GLOBAL"}, token=admin_tok)
check("admin cannot grant roles to self -> 403 SELF_GRANT", s == 403 and b.get("code") == "SELF_GRANT", (s, b))
s, b, _ = call("POST", f"{ID}/api/v1/admin/users/{staff['BRC'][3]}/grants",
               {"role": "ADMIN", "scopeType": "STATION", "scopeValue": "BRC"}, token=admin_tok)
check("ADMIN can't be station-scoped -> 422", s == 422, (s, b))

wait_fresh_step()
_, brc_tok, _ = login_staff(*staff["BRC"][:3])
_, hwh_tok, _ = login_staff(*staff["HWH"][:3])
check("staff token carries scoped grant", claims(brc_tok)["grants"] == ["STATION_MASTER:STATION:BRC"], claims(brc_tok))

# --- 3. Passenger flow
pemail = f"asha.{uuid.uuid4().hex[:6]}@example.com"
s, _, _ = call("POST", f"{ID}/api/v1/auth/register", {"email": pemail, "password": "short", "phone": "9820012345"})
check("weak password rejected -> 422", s == 422, s)
s, _, _ = call("POST", f"{ID}/api/v1/auth/register",
               {"email": pemail.upper(), "password": "monsoon-express-1950", "phone": "9820012345"})
check("passenger registers -> 201", s == 201, s)
s, b, h = call("POST", f"{ID}/api/v1/auth/login", {"email": pemail, "password": "monsoon-express-1950"})
p_tok, p_rt = b.get("accessToken"), rt_cookie(h)
check("login is case-insensitive on email; passenger gets token + HttpOnly rt cookie",
      s == 200 and p_tok and p_rt and "HttpOnly" in (h.get("Set-Cookie") or ""), (s, b))

booking = {"trainRunId": 1, "fromStopSeq": 1, "toStopSeq": 4, "travelClass": "3A", "quota": "GN",
           "contactPhone": "9820012345", "passengers": [{"name": "Asha Rao", "age": 52, "gender": "F"}]}
s, b, _ = call("POST", f"{RES}/api/v1/bookings", booking, token=p_tok, headers={"Idempotency-Key": "sec-" + uuid.uuid4().hex})
pnr = b.get("pnr")
check("passenger books with JWT -> 201", s == 201 and pnr, (s, b))
s, _, _ = call("POST", f"{RES}/api/v1/bookings", booking, headers={"Idempotency-Key": "sec-" + uuid.uuid4().hex})
check("booking without token -> 401", s == 401, s)
s, _, _ = call("POST", f"{RES}/api/v1/bookings", booking, token=brc_tok, headers={"Idempotency-Key": "sec-" + uuid.uuid4().hex})
check("station master can't book as a passenger -> 403", s == 403, s)

other = f"ravi.{uuid.uuid4().hex[:6]}@example.com"
call("POST", f"{ID}/api/v1/auth/register", {"email": other, "password": "deccan-queen-1930", "phone": "9820099999"})
_, b, _ = call("POST", f"{ID}/api/v1/auth/login", {"email": other, "password": "deccan-queen-1930"})
s, _, _ = call("GET", f"{RES}/api/v1/bookings/{pnr}", token=b["accessToken"])
check("another passenger can't read this PNR -> 404 (no existence oracle)", s == 404, s)

# --- 4. Scoped staff access (ABAC)
s, b, _ = call("GET", f"{RES}/api/v1/staff/runs/1/occupancy", token=brc_tok)
check("BRC station master sees run 1 (calls at BRC) -> 200", s == 200 and isinstance(b, list), (s, b))
s, b, _ = call("GET", f"{RES}/api/v1/staff/runs/1/occupancy", token=hwh_tok)
check("HWH station master can't see run 1 -> 403", s == 403, (s, b))
s, _, _ = call("GET", f"{RES}/api/v1/staff/runs/1/occupancy", token=p_tok)
check("passenger can't reach staff API -> 403", s == 403, s)

# --- 5. Token forgery
hdr, pay, sig = p_tok.split(".")
forged_payload = base64.urlsafe_b64encode(json.dumps({**claims(p_tok), "roles": ["ADMIN"]}).encode()).decode().rstrip("=")
s, _, _ = call("GET", f"{RES}/api/v1/staff/runs/1/occupancy", token=f"{hdr}.{forged_payload}.{sig}")
check("payload edited to roles=[ADMIN] -> 401 (signature)", s == 401, s)
none_hdr = base64.urlsafe_b64encode(b'{"alg":"none"}').decode().rstrip("=")
s, _, _ = call("GET", f"{RES}/api/v1/bookings/{pnr}", token=f"{none_hdr}.{pay}.")
check("alg=none token -> 401", s == 401, s)

# --- 6. Refresh rotation and theft detection
s, b, h = call("POST", f"{ID}/api/v1/auth/refresh", cookie=p_rt)
check("refresh without CSRF header -> 403", s == 403, s)
s, b, h = call("POST", f"{ID}/api/v1/auth/refresh", cookie=p_rt, headers={"X-Requested-With": "railops-web"})
p_rt2 = rt_cookie(h)
check("refresh rotates the cookie", s == 200 and p_rt2 and p_rt2 != p_rt, s)
s, _, _ = call("POST", f"{ID}/api/v1/auth/refresh", cookie=p_rt, headers={"X-Requested-With": "railops-web"})
check("old token reused within 5 s grace (two tabs) -> 200", s == 200, s)
time.sleep(6)
s, _, _ = call("POST", f"{ID}/api/v1/auth/refresh", cookie=p_rt, headers={"X-Requested-With": "railops-web"})
check("old token replayed after grace (theft) -> 401", s == 401, s)
s, _, _ = call("POST", f"{ID}/api/v1/auth/refresh", cookie=p_rt2, headers={"X-Requested-With": "railops-web"})
check("...and the whole family is revoked: the legitimate newer token dies too -> 401", s == 401, s)

# --- 7. Brute force lockout
for _ in range(5):
    call("POST", f"{ID}/api/v1/auth/login", {"email": other, "password": "wrong-password-xx"})
s, b, _ = call("POST", f"{ID}/api/v1/auth/login", {"email": other, "password": "deccan-queen-1930"})
check("after 5 failures even the right password is refused (locked) -> 401", s == 401, s)
s2, b2, _ = call("POST", f"{ID}/api/v1/auth/login", {"email": "nobody@nowhere.in", "password": "whatever-123456"})
check("locked account and unknown user return the identical error", b.get("detail") == b2.get("detail") and s == s2, (b, b2))

# --- 8. Role revocation kills sessions
_, _, brc_rt = login_staff(*staff["BRC"][:3]) if False else (None, None, None)
wait_fresh_step()
s, _, brc_rt = login_staff(*staff["BRC"][:3])
s, b, _ = call("GET", f"{ID}/api/v1/admin/audit/verify", token=admin_tok)
grant_id = subprocess.run(["psql", "-h", "localhost", "-U", "railops", "-d", "railops", "-tAc",
                           f"SELECT id FROM identity.user_role WHERE user_id='{staff['BRC'][3]}'"],
                          capture_output=True, text=True, env={"PGPASSWORD": "railops", "PATH": "/usr/bin"}).stdout.strip()
s, _, _ = call("DELETE", f"{ID}/api/v1/admin/grants/{grant_id}", token=admin_tok)
s2, _, _ = call("POST", f"{ID}/api/v1/auth/refresh", cookie=brc_rt, headers={"X-Requested-With": "railops-web"})
check("revoking a grant revokes the user's refresh tokens -> 401", s == 204 and s2 == 401, (s, s2))

# --- 9. Audit chain
s, b, _ = call("GET", f"{ID}/api/v1/admin/audit/verify", token=admin_tok)
check(f"audit chain intact ({b.get('entriesChecked')} entries)", s == 200 and b.get("intact") is True, b)
print(f"\n{sum(1 for _, ok in results if ok)}/{len(results)} checks passed")
sys.exit(0 if all(ok for _, ok in results) else 1)
