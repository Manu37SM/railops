"""Shared helpers for incident drills (scripts/chaos/*.py). Local stack: scripts/start-local.sh."""
import json, os, subprocess, threading, time, urllib.error, urllib.request, uuid

ID, RES, PAY, GW = "http://localhost:8082", "http://localhost:8081", "http://localhost:8083", "http://localhost:8080"
T0 = time.time()
TIMELINE = []


def log(event):
    line = f"T+{time.time() - T0:6.1f}s  {event}"
    TIMELINE.append(line)
    print(line, flush=True)


def call(method, url, body=None, token=None, headers=None, timeout=30):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method, headers=h)
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw, st = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, st = e.read(), e.code
    except Exception as e:                      # timeouts, refused: an outcome, not a crash of the drill
        return 599, {"error": type(e).__name__, "detail": str(e)}, time.perf_counter() - t
    parsed = json.loads(raw) if raw[:1] in (b"{", b"[") else raw.decode(errors="replace")
    return st, parsed, time.perf_counter() - t


def sql(q):
    env = {**os.environ, "PGPASSWORD": "railops"}
    return subprocess.run(["psql", "-h", "localhost", "-U", "railops", "-d", "railops", "-tAc", q],
                          capture_output=True, text=True, env=env).stdout.strip()


def passenger(tag="drill"):
    email = f"{tag}.{uuid.uuid4().hex[:8]}@example.com"
    call("POST", f"{ID}/api/v1/auth/register", {"email": email, "password": "drill-password-123",
                                               "phone": "9%09d" % (uuid.uuid4().int % 10**9)})
    return call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": "drill-password-123"})[1]["accessToken"]


def book(tok, quota="GN", name="Drill Passenger"):
    st, b, dt = call("POST", f"{RES}/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4,
                     "travelClass": "3A", "quota": quota, "contactPhone": "9820012345",
                     "passengers": [{"name": name, "age": 35, "gender": "F"}]}, tok, {"Idempotency-Key": uuid.uuid4().hex})
    return st, b, dt


def intent(tok, pnr):
    return call("POST", f"{RES}/api/v1/bookings/{pnr}/payment-intent", token=tok)


def customer_pays(checkout_url):
    order = checkout_url.rstrip("/").rsplit("/", 1)[1]
    return call("POST", f"{PAY}/sim-gateway/orders/{order}/pay")


def booking(tok, pnr):
    return call("GET", f"{RES}/api/v1/bookings/{pnr}", token=tok)[1]


def metric(port, name):
    """Sum of a Prometheus metric's samples (all tags)."""
    try:
        body = urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=5).read().decode()
    except Exception:
        return None
    return sum(float(l.rsplit(" ", 1)[1]) for l in body.splitlines() if l.startswith(name) and not l.startswith("#"))


def iptables(action, port):
    subprocess.run(["sudo", "iptables", "-A" if action == "block" else "-D", "INPUT", "-p", "tcp",
                    "--dport", str(port), "-j", "DROP"], check=True)


def kafka_port():
    return int(open("/tmp/railops-kafka.bootstrap").read().strip().rsplit(":", 1)[1])


def ledger_balanced():
    return sql("SELECT count(*) FROM (SELECT txn_id FROM payment.ledger_entry GROUP BY txn_id "
               "HAVING sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) <> 0) x") == "0"


def double_booked():
    return int(sql("""SELECT count(*) FROM reservation.allocation a JOIN reservation.allocation b
                      ON a.run_coach_id=b.run_coach_id AND a.berth_no=b.berth_no AND a.id<b.id
                      AND a.seg_range && b.seg_range AND a.slot_range && b.slot_range""") or 0)
