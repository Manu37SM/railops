"""DRILL: an operator's psql session holds row locks on a hot pool (BEGIN; UPDATE ...; then nothing).

Questions: what do users see, does anything pile up (threads, connections), what's the blast radius (other quotas,
other trains, reads), can on-call find the culprit from the signals they have, and how fast does it recover?
"""
import os, sys, subprocess, threading, time, collections, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

HOLD_S = int(os.environ.get("HOLD_S", "40"))
# BREAKGLASS=1: the operator uses the ops_breakglass role (idle_in_transaction_session_timeout=60s, lock_timeout=2s)
# as deploy/compose/postgres/10-roles.sh defines it, instead of the application's own role.
BREAKGLASS = os.environ.get("BREAKGLASS") == "1"
if BREAKGLASS:
    subprocess.run(["sudo", "-u", "postgres", "psql", "-d", "railops", "-qc", """
        DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ops_breakglass') THEN
          CREATE ROLE ops_breakglass LOGIN PASSWORD 'drill-only'; END IF; END $$;
        ALTER ROLE ops_breakglass LOGIN;
        ALTER ROLE ops_breakglass SET idle_in_transaction_session_timeout = '20s';
        ALTER ROLE ops_breakglass SET lock_timeout = '2s';
        GRANT railops TO ops_breakglass;
        GRANT pg_read_all_stats TO railops;   -- the drill's on-call queries run as railops; in prod: ops_readonly"""],
        check=True, capture_output=True)
sql("UPDATE reservation.segment_inventory SET cnf_capacity = cnf_used + 200 WHERE train_run_id=1")
sql("UPDATE reservation.waitlist_counter SET wl_cap = next_seq + 200 WHERE train_run_id=1")
toks = [passenger() for _ in range(12)]

def burst(n, quota="GN"):
    out = collections.Counter(); lat = []
    lock = threading.Lock()
    def one(i):
        st, b, dt = book(toks[i % len(toks)], quota=quota, name="Lock Drill")
        with lock:
            code = b.get("code") if isinstance(b, dict) else None
            out[f"{st}{'/' + code if code else ''}"] += 1; lat.append(dt)
    ts = [threading.Thread(target=one, args=(i,)) for i in range(n)]
    [t.start() for t in ts]; [t.join() for t in ts]
    lat.sort()
    return dict(out), (lat[len(lat) // 2] * 1000 if lat else 0), (lat[-1] * 1000 if lat else 0)

r = burst(8)
log(f"baseline GN burst: {r[0]} p50={r[1]:.0f}ms max={r[2]:.0f}ms")

# ---- the operator's session (application_name as psql would report it)
user, pw = ("ops_breakglass", "drill-only") if BREAKGLASS else ("railops", "railops")
op = subprocess.Popen(["psql", "-h", "localhost", "-U", user, "-d", "railops", "-q"], stdin=subprocess.PIPE,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True,
                      env={**os.environ, "PGPASSWORD": pw, "PGAPPNAME": "psql"})
op.stdin.write("BEGIN;\nUPDATE reservation.segment_inventory SET cnf_capacity = cnf_capacity "
               "WHERE train_run_id = 1 AND class_code = '3A' AND quota_code = 'GN';\n")
op.stdin.flush()
time.sleep(1)
log("FAULT: operator session holds FOR UPDATE locks on run 1 / 3A / GN and goes idle (no COMMIT)")

retry_key = "drill-retry-" + uuid.uuid4().hex
st, b, _ = call("POST", f"{RES}/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A",
                "quota": "GN", "contactPhone": "9820012345", "passengers": [{"name": "Retry Later", "age": 30, "gender": "M"}]},
                toks[0], {"Idempotency-Key": retry_key})
log(f"  one request with key {retry_key[-6:]}: {st} {b.get('code') if isinstance(b, dict) else b} retryable={b.get('retryable') if isinstance(b, dict) else None}")
for label, quota in [("GN (locked pool)", "GN"), ("TQ (same train, other quota)", "TQ")]:
    r = burst(12, quota)
    log(f"  {label}: {r[0]}  p50={r[1]:.0f}ms max={r[2]:.0f}ms")
st, av, dt = call("GET", f"{RES}/api/v1/runs/1/availability?from=0&to=4&class=3A")
log(f"  availability read: {st} in {dt*1000:.0f} ms")
log(f"  hikari active/pending: {metric(8081, 'hikaricp_connections_active')}/{metric(8081, 'hikaricp_connections_pending')}"
    f" | pool_gate rejected: {metric(8081, 'inventory_pool_gate_rejected_total')}")

# ---- what on-call runs (docs/runbooks/db-lock-contention.md)
log("DIAGNOSIS 1 (classic blocking tree, only shows WAITERS that exist right now):")
tree = sql("""SELECT string_agg(format('blocked pid=%s app=%s waiting=%ss  <-  blocker pid=%s app=%s state=%s idle=%ss query=%s',
                     w.pid, w.application_name, extract(epoch from now()-w.query_start)::int,
                     b.pid, b.application_name, b.state, extract(epoch from now()-b.state_change)::int, left(b.query, 60)), E'\\n')
              FROM pg_stat_activity w
              JOIN LATERAL unnest(pg_blocking_pids(w.pid)) bp(pid) ON true
              JOIN pg_stat_activity b ON b.pid = bp.pid""")
for line in (tree or "  (nothing blocked at this instant)").splitlines()[:4]:
    log("  " + line)
# docs/runbooks/db-lock-contention.md, query 2: find lock HOLDERS (works with nobody waiting at this instant)
holders = sql("""SELECT string_agg(format('pid=%s user=%s app=%s client=%s xact_age=%ss idle=%ss tables=%s',
                        a.pid, a.usename, a.application_name, coalesce(a.client_addr::text,'local'),
                        extract(epoch from now()-a.xact_start)::int, extract(epoch from now()-a.state_change)::int,
                        t.tables), E'\n')
                 FROM pg_stat_activity a
                 JOIN LATERAL (SELECT string_agg(DISTINCT c.relname, ',') AS tables FROM pg_locks l
                               JOIN pg_class c ON c.oid = l.relation
                               WHERE l.pid = a.pid AND l.mode IN ('RowExclusiveLock','RowShareLock','ExclusiveLock','AccessExclusiveLock')
                                 AND c.relnamespace = 'reservation'::regnamespace) t ON t.tables IS NOT NULL
                 WHERE a.state LIKE 'idle in transaction%' AND a.xact_start < now() - interval '5 seconds'""")
log("DIAGNOSIS 2 (lock HOLDERS idle in a transaction):")
for line in (holders or "  none").splitlines():
    log("  " + line)
blocker = holders.split("pid=")[1].split()[0] if holders else ""
log(f"  culprit: pid={blocker}")

if BREAKGLASS:
    t_kill = time.time()
    if not blocker:
        sys.exit("diagnosis found no holder: the runbook query is blind (check pg_read_all_stats)")
    while sql(f"SELECT count(*) FROM pg_stat_activity WHERE pid = {blocker}") == "1" and time.time() - t_kill < 90:
        time.sleep(1)
    alive = sql(f"SELECT count(*) FROM pg_stat_activity WHERE pid = {blocker}") == "1"
    log("SELF-HEAL: PostgreSQL terminated the idle break-glass session (idle_in_transaction_session_timeout)"
        if not alive else "NO SELF-HEAL: session still alive after 90 s")
else:
    time.sleep(max(0, HOLD_S - (time.time() - T0)))
    t_kill = time.time()
    sql(f"SELECT pg_terminate_backend({blocker})")
    log(f"MITIGATION: pg_terminate_backend({blocker}) (the operator's uncommitted change is rolled back)")
r = burst(8)
log(f"after mitigation GN burst: {r[0]} p50={r[1]:.0f}ms max={r[2]:.0f}ms (recovered {time.time() - t_kill:.1f}s after kill)")
st, b, _ = call("POST", f"{RES}/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A",
                "quota": "GN", "contactPhone": "9820012345", "passengers": [{"name": "Retry Later", "age": 30, "gender": "M"}]},
                toks[0], {"Idempotency-Key": retry_key})
log(f"client retries the failed request with the SAME Idempotency-Key: {st} (expected 201: the failed attempt left nothing behind)")
op.kill()
log(f"SUMMARY: double_booked={double_booked()} | scheduler failures={metric(8081, 'scheduled_task_failures_total')}")
open("/tmp/drill-dblock.timeline", "w").write("\n".join(TIMELINE))
