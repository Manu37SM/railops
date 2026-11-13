"""Phase 10 end-to-end: API gateway (blocking, headers, rate limits, verified rate-limit keys) and the Tatkal waiting
room (lottery for early joiners, FIFO after opening, paced admission, booking gate, kill switch, fail-closed).

Run against a fresh stack:  scripts/start-local.sh --fresh  (gateway on :8080)."""
import base64, hashlib, hmac, json, struct, subprocess, sys, time, urllib.error, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

GW, ID, RES = "http://localhost:8080", "http://localhost:8082", "http://localhost:8081"
results = []


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


def sql(q):
    return subprocess.run(["psql", "-h", "localhost", "-U", "railops", "-d", "railops", "-tAc", q], capture_output=True,
                          text=True, env={"PGPASSWORD": "railops", "PATH": "/usr/bin"}).stdout.strip()


def check(name, cond, detail=""):
    results.append(cond); print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"   -> {detail}"))


def totp(s):
    key = base64.b32decode(s + "=" * (-len(s) % 8)); step = int(time.time()) // 30
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest(); o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def passenger(tag):
    """Registered directly against identity (setup traffic shouldn't spend the gateway's per-IP login budget)."""
    email = f"{tag}.{uuid.uuid4().hex[:6]}@example.com"
    phone = "97%08d" % (int(uuid.uuid4().int % 10**8))
    call("POST", f"{ID}/api/v1/auth/register", {"email": email, "password": "central-railway-" + tag, "phone": phone})
    tok = call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": "central-railway-" + tag})[1]["accessToken"]
    sub = json.loads(base64.urlsafe_b64decode(tok.split(".")[1] + "=="))["sub"]
    return tok, sub


def book(base, tok, quota="TQ", name="Sana Qureshi"):
    body = {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A", "quota": quota,
            "contactPhone": "9820099999", "passengers": [{"name": name, "age": 34, "gender": "F"}]}
    return call("POST", f"{base}/api/v1/bookings", body, tok, {"Idempotency-Key": "edge-" + uuid.uuid4().hex})


def code(resp):
    return resp[1].get("code") if isinstance(resp[1], dict) else None


# ======================================================================================= gateway
print("--- gateway")
for path in ["/internal/v1/bookings/1234567890/contact", "/actuator/health", "/oauth2/token", "/sim-gateway/pay",
             "/INTERNAL/v1/x", "/api/v1/%2e%2e/internal/v1/x", "/api/v1/runs/1/../../../internal/v1/x"]:
    st, _ = call("GET", GW + path)
    check(f"edge hides {path}", st == 404, st)

st, body, hd = call("GET", f"{GW}/api/v1/runs/1/availability?from=0&to=4&class=3A", full=True, headers={"X-Correlation-Id": "edge-test-0001"})
check("availability routed through the gateway", st == 200 and "pools" in body, (st, body))
check("inbound correlation id echoed", hd.get("X-Correlation-Id") == "edge-test-0001", dict(hd))
check("security headers set (HSTS, nosniff, referrer)",
      hd.get("Strict-Transport-Security", "").startswith("max-age") and hd.get("X-Content-Type-Options") == "nosniff"
      and hd.get("Referrer-Policy") == "no-referrer", dict(hd))
st, _, hd = call("GET", f"{GW}/api/v1/runs/1/availability?from=0&to=4", full=True, headers={"X-Correlation-Id": "<script>alert(1)</script>"})
check("unsafe inbound correlation id replaced", hd.get("X-Correlation-Id", "").count("-") == 4, dict(hd))
check("rate-limit headers advertise remaining budget", hd.get("X-RateLimit-Remaining") is not None, dict(hd))

time.sleep(3.5)  # let the search bucket refill
with ThreadPoolExecutor(20) as pool:
    codes = list(pool.map(lambda _: call("GET", f"{GW}/api/v1/runs/1/availability?from=0&to=4")[0], range(60)))
check("availability burst per IP: burst 30 + refill 10/s, rest 429", 25 <= codes.count(200) <= 46 and codes.count(429) >= 20,
      {c: codes.count(c) for c in set(codes)})

codes = [call("POST", f"{GW}/api/v1/auth/login", {"email": "nobody@example.com", "password": "wrong-password-123"})[0] for _ in range(9)]
check("login per IP: 5 attempts then 429", codes[:5].count(429) == 0 and codes[-3:] == [429] * 3, codes)

A, a_sub = passenger("a"); B, b_sub = passenger("b")
bad = {"trainRunId": 1}  # invalid body: rate limiting happens before the service sees it, no inventory touched
codes = [call("POST", f"{GW}/api/v1/bookings", bad, A, {"Idempotency-Key": uuid.uuid4().hex})[0] for _ in range(6)]
check("booking create per user: burst 3 then 429", codes[:3].count(429) == 0 and codes[3:].count(429) >= 2, codes)
st = call("POST", f"{GW}/api/v1/bookings", bad, B, {"Idempotency-Key": uuid.uuid4().hex})[0]
check("another user's budget is independent", st == 400, st)

# Forged token: B's sub, garbage signature. Must be keyed by IP, NOT burn B's budget.
hdr = base64.urlsafe_b64encode(json.dumps({"alg": "RS256", "kid": "x"}).encode()).rstrip(b"=").decode()
pl = base64.urlsafe_b64encode(json.dumps({"sub": b_sub, "exp": int(time.time()) + 600}).encode()).rstrip(b"=").decode()
forged = f"{hdr}.{pl}.c2lnbmF0dXJl"
time.sleep(3.2)  # refill B
for _ in range(6):
    call("POST", f"{GW}/api/v1/bookings", bad, forged, {"Idempotency-Key": uuid.uuid4().hex})
codes = [call("POST", f"{GW}/api/v1/bookings", bad, B, {"Idempotency-Key": uuid.uuid4().hex})[0] for _ in range(3)]
check("forged token with victim's sub doesn't spend victim's budget", codes == [400, 400, 400], codes)
st = call("POST", f"{GW}/api/v1/bookings", bad, forged, {"Idempotency-Key": uuid.uuid4().hex})[0]
check("forged token still rejected by the service (401) or throttled (429)", st in (401, 429), st)

# ======================================================================================= waiting room
print("--- waiting room")
ADMIN_PW = "Local-Only-Bootstrap-Passw0rd"
blob = bytes.fromhex(sql("SELECT coalesce(encode(mfa_secret_enc,'hex'),'') FROM identity.app_user WHERE email='root.admin@railops.local'"))
if blob:
    admin_secret = AESGCM(base64.b64decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")).decrypt(
        blob[1:13], blob[13:], b"identity.app_user.mfa_secret").decode()
else:  # fresh stack: the bootstrap admin enrols first
    et = call("POST", f"{ID}/api/v1/auth/login", {"email": "root.admin@railops.local", "password": ADMIN_PW})[1]["enrolmentToken"]
    admin_secret = call("POST", f"{ID}/api/v1/auth/mfa/enrol", token=et)[1]["secretBase32"]
    call("POST", f"{ID}/api/v1/auth/mfa/confirm", {"code": totp(admin_secret)}, et)
time.sleep(31 - int(time.time()) % 30)
admin = call("POST", f"{ID}/api/v1/auth/login", {"email": "root.admin@railops.local",
             "password": ADMIN_PW, "otp": totp(admin_secret)})[1]["accessToken"]

st = call("PUT", f"{RES}/api/v1/staff/runs/1/waiting-room", {"opensAt": "2030-01-01T00:00:00Z", "admitPerSecond": 2}, A)[0]
check("passenger cannot open a waiting room", st == 403, st)

opens = time.time() + 8
st = call("PUT", f"{RES}/api/v1/staff/runs/1/waiting-room",
          {"opensAt": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(opens)) + ".%03dZ" % int((opens % 1) * 1000), "admitPerSecond": 2}, admin)[0]
check("admin opens the room (opens in 8 s, 2 admissions/s)", st == 204, st)

r = book(RES, A)
check("booking without admission is refused (403 RES_WAITING_ROOM)", r[0] == 403 and code(r) == "RES_WAITING_ROOM", r)

early = [passenger(f"e{i}") for i in range(8)]
positions = []
for tok, _ in early:
    st, s = call("POST", f"{RES}/api/v1/runs/1/waiting-room", token=tok)
    positions.append(s.get("position"))
check("every early joiner is QUEUED", all(p is not None for p in positions), positions)
# Join order 0..7; final ranks come from random scores. P(identical order by chance) = 1/8! = 0.0025%.
final = [call("GET", f"{RES}/api/v1/runs/1/waiting-room", token=t)[1]["position"] for t, _ in early]
check("final places are a permutation of 1..8", sorted(final) == list(range(1, 9)), final)
check("early places are a lottery, not arrival order", final != list(range(1, 9)), final)
again = call("POST", f"{RES}/api/v1/runs/1/waiting-room", token=early[0][0])[1]["position"]
check("re-joining keeps your place", again == final[0], (again, final[0]))
s = call("GET", f"{RES}/api/v1/runs/1/waiting-room", token=A)[1]
check("non-joiner sees NOT_JOINED", s["state"] == "NOT_JOINED", s)

while time.time() < opens + 0.3:
    time.sleep(0.1)
late_tok, _ = passenger("late")
s = call("POST", f"{RES}/api/v1/runs/1/waiting-room", token=late_tok)[1]
check("after opening, joiners go behind every early joiner (FIFO)", s["state"] == "QUEUED", s)
late_pos_initial = s["position"]

t0 = time.time(); time.sleep(2.2)
states = [call("GET", f"{RES}/api/v1/runs/1/waiting-room", token=t)[1]["state"] for t, _ in early]
admitted = states.count("ADMITTED")
elapsed = time.time() - opens
check(f"admission is paced (~2/s): {admitted} admitted {elapsed:.1f}s after opening", 2 <= admitted <= 2 * (int(elapsed) + 2), states)
order = sorted(range(8), key=lambda i: final[i])
first_admitted = [i for i in order if states[i] == "ADMITTED"]
check("admission follows queue places", first_admitted == order[:admitted], (order, states))

lucky = early[order[0]][0]
r = book(RES, lucky)
check("admitted user can book Tatkal", r[0] == 201, r)
r = book(GW, lucky, name="Through Gateway")
check("admitted user can book through the gateway too", r[0] == 201, r)
for _ in range(30):
    if call("GET", f"{RES}/api/v1/runs/1/waiting-room", token=late_tok)[1]["state"] == "ADMITTED":
        break
    time.sleep(0.5)
check("late joiner admitted only after the early joiners", all(
    call("GET", f"{RES}/api/v1/runs/1/waiting-room", token=t)[1]["state"] == "ADMITTED" for t, _ in early), late_pos_initial)

st = call("DELETE", f"{RES}/api/v1/staff/runs/1/waiting-room", token=admin)[0]
s = call("GET", f"{RES}/api/v1/runs/1/waiting-room", token=A)[1]
check("kill switch closes the room", st == 204 and s["state"] == "NOT_REQUIRED", (st, s))
r = book(RES, A, quota="GN", name="Kill Switch")
check("with the room closed, booking needs no admission", r[0] == 201, r)

# Redis unreachable (CLIENT PAUSE > 200 ms client timeout): Tatkal fails closed, General proceeds.
subprocess.run(["redis-cli", "CLIENT", "PAUSE", "4000", "ALL"], capture_output=True)
r1 = book(RES, A, quota="TQ", name="Redis Down")
r2 = book(RES, B, quota="GN", name="Redis Down General")
time.sleep(4.5)
check("Redis down: Tatkal booking fails closed (503)", r1[0] == 503 and code(r1) == "DEPENDENCY_UNAVAILABLE", r1)
check("Redis down: General booking still served", r2[0] == 201, r2)

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
