"""Hot-pool booking throughput: every request hits the SAME run/class/quota, so every allocation serialises on that
pool's counter rows (ORDER BY seg_idx FOR UPDATE). This is the worst case the Phase 3 design sized at ~100-200
allocations/s per pool. Calls reservation directly (the gateway's per-user limit would otherwise be the bottleneck).

Usage: python3 -I scripts/bench_booking.py [users] [threads] [seconds] [passengers_per_booking]"""
import json, subprocess, sys, threading, time, urllib.error, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor

ID, RES = "http://localhost:8082", "http://localhost:8081"
USERS, THREADS, SECONDS, PAX = (int(a) for a in (sys.argv[1:] + ["40", "32", "20", "1"][len(sys.argv) - 1:]))


def sql(q):
    return subprocess.run(["psql", "-h", "localhost", "-U", "railops", "-d", "railops", "-tAc", q], capture_output=True,
                          text=True, env={"PGPASSWORD": "railops", "PATH": "/usr/bin"}).stdout.strip()


def post(url, body, tok=None, key=None):
    h = {"Content-Type": "application/json"}
    if tok: h["Authorization"] = "Bearer " + tok
    if key: h["Idempotency-Key"] = key
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        raw = e.read(); return e.code, (json.loads(raw) if raw[:1] == b"{" else {})


# Physical berths: EXTRA_COACHES more 3A coaches (default 10 -> 12 coaches, 768 berths: a long real rake for one
# class). Keep the run short enough not to sell out, or later bookings measure the WL path instead.
import os
EXTRA = int(os.environ.get("EXTRA_COACHES", "10"))
sql(f"""INSERT INTO reservation.run_coach (id, train_run_id, coach_label, coach_type, class_code, position)
       SELECT 9000 + g, 1, 'B' || (100 + g), c.coach_type, '3A', 100 + g
       FROM generate_series(1, {EXTRA}) g, (SELECT coach_type FROM reservation.run_coach WHERE train_run_id=1 AND class_code='3A' LIMIT 1) c
       ON CONFLICT DO NOTHING""")
sql("UPDATE reservation.segment_inventory SET cnf_capacity=100000, rac_capacity=0 WHERE train_run_id=1 AND class_code='3A' AND quota_code='GN'")


def user(i):
    e = f"bench{i}.{uuid.uuid4().hex[:6]}@example.com"
    post(f"{ID}/api/v1/auth/register", {"email": e, "password": "bench-password-123", "phone": "95%08d" % (uuid.uuid4().int % 10**8)})
    return post(f"{ID}/api/v1/auth/login", {"email": e, "password": "bench-password-123"})[1]["accessToken"]


with ThreadPoolExecutor(8) as p:
    tokens = list(p.map(user, range(USERS)))

stop, lock = time.time() + SECONDS, threading.Lock()
lat, codes = [], {}
pax = [{"name": "Bench Passenger " + "ABCDEF"[k], "age": 30 + k, "gender": "M"} for k in range(PAX)]


def worker(n):
    tok = tokens[n % USERS]
    while time.time() < stop:
        t = time.perf_counter()
        st, _ = post(f"{RES}/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A",
                     "quota": "GN", "contactPhone": "9820000000", "passengers": pax}, tok, "bench-" + uuid.uuid4().hex)
        with lock:
            lat.append(time.perf_counter() - t); codes[st] = codes.get(st, 0) + 1


waits = {}


def cpu():
    v = [int(x) for x in open("/proc/stat").readline().split()[1:]]
    return sum(v), v[3] + v[4]


def sampler():
    """Where are the DB sessions while the hot pool is hammered? Lock waits => design limit; CPU => box limit."""
    while time.time() < stop:
        for row in sql("SELECT coalesce(wait_event_type,'CPU')||':'||coalesce(wait_event,'running') FROM pg_stat_activity "
                       "WHERE datname='railops' AND state='active' AND pid<>pg_backend_pid()").splitlines():
            waits[row] = waits.get(row, 0) + 1
        time.sleep(0.2)


before = int(sql("SELECT count(*) FROM reservation.allocation") or 0)
t0, c0 = time.time(), cpu()
threading.Thread(target=sampler, daemon=True).start()
with ThreadPoolExecutor(THREADS) as p:
    list(p.map(worker, range(THREADS)))
dur, c1 = time.time() - t0, cpu()
busy = 1 - (c1[1] - c0[1]) / max(1, c1[0] - c0[0])
after = int(sql("SELECT count(*) FROM reservation.allocation") or 0)
lat.sort()
pct = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))] * 1000
print(f"threads={THREADS} pax/booking={PAX} duration={dur:.1f}s codes={codes}")
print(f"bookings/s={codes.get(201, 0) / dur:.1f}  allocations/s={(after - before) / dur:.1f}  "
      f"p50={pct(.5):.0f}ms p95={pct(.95):.0f}ms p99={pct(.99):.0f}ms")
print(f"host CPU busy={busy:.0%}  db session states: " + ", ".join(f"{k}={v}" for k, v in sorted(waits.items(), key=lambda x: -x[1])[:6]))
