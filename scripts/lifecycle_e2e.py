"""Phase 7 end-to-end: availability, booking, payment intent, cancellation, chart preparation, pagination.
Pool sized tiny (3A/GN: 3 CNF, 2 RAC slots, WL cap 5) so every transition happens with a handful of bookings.

PaymentCaptured normally arrives over Kafka (Phase 9). There's no broker here, so step 5 applies the
confirmation's DB effects directly and says so; everything else goes through the real APIs."""
import base64, hashlib, hmac, json, struct, subprocess, sys, time, urllib.error, urllib.request, uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ID, RES = "http://localhost:8082", "http://localhost:8081"
results = []


def call(method, url, body=None, token=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read(); return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw.decode())
    except urllib.error.HTTPError as e:
        raw = e.read(); return e.code, (json.loads(raw) if raw[:1] == b"{" else raw.decode())


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
    email = f"{tag}.{uuid.uuid4().hex[:6]}@example.com"
    phone = "98%08d" % (int(uuid.uuid4().int % 10**8))  # phone is unique per account (login identifier)
    call("POST", f"{ID}/api/v1/auth/register", {"email": email, "password": "western-railway-" + tag, "phone": phone})
    return call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": "western-railway-" + tag})[1]["accessToken"]


def book(tok, pax, quota="GN"):
    body = {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A", "quota": quota,
            "contactPhone": "9820011111", "passengers": pax}
    return call("POST", f"{RES}/api/v1/bookings", body, tok, {"Idempotency-Key": "lc-" + uuid.uuid4().hex})


def statuses(b):
    return [p["currentStatus"].split("/")[0] for p in b["passengers"]]


A, B, C, D, E = (passenger(t) for t in "abcde")

# 1. Public availability
s, av = call("GET", f"{RES}/api/v1/runs/1/availability?from=0&to=4&class=3A")
gn = next(p for p in av["pools"] if p["quotaCode"] == "GN")
check("availability is public and shows AVAILABLE-3", s == 200 and gn["display"] == "AVAILABLE-3", av)

# 2. Fill the pool: CNF x3, RAC x2, WL x2
s, a = book(A, [{"name": "Meera Iyer", "age": 41, "gender": "F"}, {"name": "Arjun Iyer", "age": 44, "gender": "M"},
                {"name": "Kavya Iyer", "age": 4, "gender": "F", "berthRequired": False}])
s, b = book(B, [{"name": "Rahul Shah", "age": 30, "gender": "M"}])
s, c = book(C, [{"name": "Farah Khan", "age": 35, "gender": "F"}, {"name": "Zoya Khan", "age": 33, "gender": "F"}])
s, d = book(D, [{"name": "Vivek Nair", "age": 29, "gender": "M"}, {"name": "Anil Nair", "age": 58, "gender": "M"}])
check("pool fills CNF,CNF,NOSB | CNF | RAC,RAC | WL,WL",
      [statuses(a), statuses(b), statuses(c), statuses(d)] == [["CNF", "CNF", "NOSB"], ["CNF"], ["RAC", "RAC"], ["WL", "WL"]],
      [statuses(x) for x in (a, b, c, d)])
# Availability is a Redis read model fed by InventoryChanged events (Phase 10): eventually consistent by design.
# Bookings re-check under the pool lock, so staleness can't oversell; it only delays what the search page shows.
t0 = time.time()
while True:
    s, av = call("GET", f"{RES}/api/v1/runs/1/availability?from=0&to=4&class=3A")
    if next(p for p in av["pools"] if p["quotaCode"] == "GN")["display"] == "WL-3" or time.time() - t0 > 5:
        break
    time.sleep(0.1)
check(f"availability converges to WL-3 (read model lag {time.time() - t0:.2f}s, bound 5s)",
      next(p for p in av["pools"] if p["quotaCode"] == "GN")["display"] == "WL-3", av)

# 3. Payment intents via service token (stub enforces aud/sub/scope + idempotency)
s1, i1 = call("POST", f"{RES}/api/v1/bookings/{a['pnr']}/payment-intent", token=A)
s2, i2 = call("POST", f"{RES}/api/v1/bookings/{a['pnr']}/payment-intent", token=A)
check("payment intent created with server fare, same intent on retry",
      s1 == 201 and i1["paymentId"] == i2["paymentId"] and float(i1["amount"]) == float(a["totalFare"]), (s1, i1, i2))
s, _ = call("POST", f"{RES}/api/v1/bookings/{a['pnr']}/payment-intent", token=B)
check("someone else's PNR -> 404", s == 404, s)
for tok, bk in ((B, b), (C, c), (D, d)):
    call("POST", f"{RES}/api/v1/bookings/{bk['pnr']}/payment-intent", token=tok)
check("payment id bound to bookings", sql(f"SELECT count(*) FROM reservation.booking WHERE payment_id IS NOT NULL") == "4")

# 4. Unpaid booking abandoned by its owner
s, e = book(E, [{"name": "Neha Joshi", "age": 26, "gender": "F"}])
s, ce = call("POST", f"{RES}/api/v1/bookings/{e['pnr']}/cancellations", {"passengerSeqs": [1]}, E, {"Idempotency-Key": "lc-" + uuid.uuid4().hex})
check("unpaid booking cancelled whole, zero refund, WL number not reused", s == 200 and ce["bookingStatus"] == "CANCELLED"
      and float(ce["refundAmount"]) == 0, (s, ce))

# 5. Simulated PaymentCaptured (Kafka path tested in Phase 11)
for bk in (a, b, c, d):
    sql(f"""UPDATE reservation.allocation SET state='CONFIRMED', hold_expires_at=NULL WHERE passenger_id IN
            (SELECT p.id FROM reservation.passenger p JOIN reservation.pnr_directory d ON d.booking_id=p.booking_id WHERE d.pnr='{bk['pnr']}');
            UPDATE reservation.waitlist_entry SET status='WAITING' WHERE status='PENDING' AND passenger_id IN
            (SELECT p.id FROM reservation.passenger p JOIN reservation.pnr_directory d ON d.booking_id=p.booking_id WHERE d.pnr='{bk['pnr']}');
            UPDATE reservation.booking SET status='CONFIRMED', hold_expires_at=NULL WHERE pnr='{bk['pnr']}';""")

# 6. Cancellation rules
s, q = call("GET", f"{RES}/api/v1/bookings/{a['pnr']}/cancellation-quote?passengers=2", token=A)
per = float(a["totalFare"]) / 2
check(">48h before departure: quote charges the 3A flat fee (180) on one adult",
      s == 200 and float(q["totalCharge"]) == 180.0 and abs(float(q["refundAmount"]) - (per - 180)) < 0.01, q)
s, err = call("POST", f"{RES}/api/v1/bookings/{a['pnr']}/cancellations", {"passengerSeqs": [1, 2]}, A, {"Idempotency-Key": "lc-" + uuid.uuid4().hex})
check("cancelling both adults but leaving the 4-year-old -> 422", s == 422 and err["code"] == "RES_INVALID_CANCELLATION", err)
key = "lc-" + uuid.uuid4().hex
s, c1 = call("POST", f"{RES}/api/v1/bookings/{a['pnr']}/cancellations", {"passengerSeqs": [2]}, A, {"Idempotency-Key": key})
s2, c2 = call("POST", f"{RES}/api/v1/bookings/{a['pnr']}/cancellations", {"passengerSeqs": [2]}, A, {"Idempotency-Key": key})
check("partial cancellation -> PARTIALLY_CANCELLED; retry replays the same cancellation",
      s == 200 and c1["bookingStatus"] == "PARTIALLY_CANCELLED" and c1["cancellationId"] == c2["cancellationId"], (c1, c2))
refunds = sql(f"SELECT count(*) FROM reservation.outbox WHERE event_type='RefundRequested' AND payload->>'pnr'='{a['pnr']}'")
check("exactly one RefundRequested despite the retry, keyed by cancellation id", refunds == "1", refunds)
# Not "cnf_used == 2": an async RAC->CNF promotion may refill the freed berth before this line runs (a race that
# made this check flaky once Phase 13 sped up the relay). What must hold: the cancelled passenger holds no berth,
# and the counter equals the real full-berth allocations on every segment.
freed = sql(f"""SELECT count(*) FROM reservation.allocation al JOIN reservation.passenger p ON p.id = al.passenger_id
                JOIN reservation.booking b ON b.id = p.booking_id AND b.run_date = p.run_date
                WHERE b.pnr = '{a['pnr']}' AND p.seq_no = 2""")
drift = sql("""SELECT count(*) FROM reservation.segment_inventory s WHERE s.quota_code = 'GN' AND s.cnf_used <>
               (SELECT count(*) FROM reservation.allocation a WHERE a.train_run_id = s.train_run_id AND a.class_code = s.class_code
                AND a.quota_code = s.quota_code AND a.seg_range @> s.seg_idx::int AND a.slot_range = '[1,3)')""")
check("cancelled berth released, and the GN counter matches real allocations", freed == "0" and drift == "0", (freed, drift))

# 7. Staff: controller scoped to BRC division can chart; station master and out-of-scope controller can't
blob = bytes.fromhex(sql("SELECT coalesce(encode(mfa_secret_enc,'hex'),'') FROM identity.app_user WHERE email='root.admin@railops.local'"))
if blob:
    admin_secret = AESGCM(base64.b64decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")).decrypt(
        blob[1:13], blob[13:], b"identity.app_user.mfa_secret").decode()
else:  # fresh stack (suite run on its own): the bootstrap admin enrols first
    et = call("POST", f"{ID}/api/v1/auth/login", {"email": "root.admin@railops.local", "password": "Local-Only-Bootstrap-Passw0rd"})[1]["enrolmentToken"]
    admin_secret = call("POST", f"{ID}/api/v1/auth/mfa/enrol", token=et)[1]["secretBase32"]
    call("POST", f"{ID}/api/v1/auth/mfa/confirm", {"code": totp(admin_secret)}, et)
time.sleep(31 - int(time.time()) % 30)
admin = call("POST", f"{ID}/api/v1/auth/login", {"email": "root.admin@railops.local",
             "password": "Local-Only-Bootstrap-Passw0rd", "otp": totp(admin_secret)})[1]["accessToken"]


def staff(role, scope_type, value):
    email = f"{role.lower()}.{value.lower()}.{uuid.uuid4().hex[:4]}@railops.local"
    _, cr = call("POST", f"{ID}/api/v1/admin/staff", {"email": email}, admin)
    call("POST", f"{ID}/api/v1/admin/users/{cr['userId']}/grants", {"role": role, "scopeType": scope_type, "scopeValue": value}, admin)
    _, lg = call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": cr["oneTimePassword"]})
    et = lg["enrolmentToken"]; _, en = call("POST", f"{ID}/api/v1/auth/mfa/enrol", token=et)
    call("POST", f"{ID}/api/v1/auth/mfa/confirm", {"code": totp(en["secretBase32"])}, et)
    return email, cr["oneTimePassword"], en["secretBase32"]


# 6b. Phase 14: advisory WL estimate (local profile enables the synthetic model)
# D is the only WL booking; the earlier partial cancellation may already have promoted its first passenger.
_, d_now = call("GET", f"{RES}/api/v1/bookings/{d['pnr']}", token=D)
waiting = sum(1 for x in statuses(d_now) if x == "WL")
s, wp = call("GET", f"{RES}/api/v1/bookings/{d['pnr']}/wl-prediction", token=D)
check(f"WL estimate for my waitlisted booking: probability, position {waiting}, disclaimer",
      s == 200 and waiting >= 1 and wp["probability"] is not None and 0 <= wp["probability"] <= 1
      and wp["currentWlPosition"] == waiting and "not a guarantee" in wp["disclaimer"], (s, wp, statuses(d_now)))
s, _ = call("GET", f"{RES}/api/v1/bookings/{d['pnr']}/wl-prediction", token=A)
check("someone else's WL estimate -> 404", s == 404, s)
s, pp = call("GET", f"{RES}/api/v1/runs/1/wl-prediction?class=3A")
check("public next-WL estimate is no more optimistic than an earlier position",
      s == 200 and pp["currentWlPosition"] == waiting + 1 and pp["probability"] is not None
      and pp["probability"] <= wp["probability"], (s, pp))
check("prediction logged once, without personal data",
      sql("SELECT count(*) FROM reservation.wl_prediction") == "1"
      and sql("SELECT count(*) FROM reservation.wl_prediction WHERE features::text ~* '(name|phone|email|9820)'") == "0")

people = [staff("CONTROLLER", "DIVISION", "BRC"), staff("CONTROLLER", "DIVISION", "HWH"), staff("STATION_MASTER", "STATION", "BRC")]
time.sleep(31 - int(time.time()) % 30)
ctl_brc, ctl_hwh, sm_brc = (call("POST", f"{ID}/api/v1/auth/login", {"email": e_, "password": p_, "otp": totp(s_)})[1]["accessToken"]
                            for e_, p_, s_ in people)
s, _ = call("POST", f"{RES}/api/v1/staff/runs/1/charts", {"chartNo": 1}, sm_brc)
check("station master may view but not prepare charts -> 403", s == 403, s)
s, _ = call("POST", f"{RES}/api/v1/staff/runs/1/charts", {"chartNo": 1}, ctl_hwh)
check("controller outside the run's divisions -> 403", s == 403, s)
s, _ = call("POST", f"{RES}/api/v1/staff/runs/1/charts", {"chartNo": 2}, ctl_brc)
check("chart 2 before chart 1 -> 409", s == 409, s)
s, ch = call("POST", f"{RES}/api/v1/staff/runs/1/charts", {"chartNo": 1}, ctl_brc)
check("chart 1 prepared by in-scope controller", s == 200 and ch["status"] == "PREPARED", ch)

_, c_after = call("GET", f"{RES}/api/v1/bookings/{c['pnr']}", token=C)
_, d_after = call("GET", f"{RES}/api/v1/bookings/{d['pnr']}", token=D)
check("chart promotion: freed CNF went to the first RAC passenger (RAC,RAC -> CNF,RAC)",
      statuses(c_after) == ["CNF", "RAC"], statuses(c_after))
check("chart: WL1 promoted into the freed RAC slot, WL2 dropped (DROP_WL_PASSENGERS)",
      statuses(d_after) == ["RAC", "CHART_CAN"] and d_after["status"] == "PARTIALLY_CANCELLED", (statuses(d_after), d_after["status"]))
check("dropped WL passenger refunded fare minus clerkage (idempotency key chart1:<pnr>)",
      sql(f"SELECT payload->>'idempotencyKey' FROM reservation.outbox WHERE event_type='RefundRequested' AND payload->>'pnr'='{d['pnr']}'") == f"chart1:{d['pnr']}")
s, again = call("POST", f"{RES}/api/v1/staff/runs/1/charts", {"chartNo": 1}, ctl_brc)
check("re-triggering chart 1 is a no-op", again.get("status") == "ALREADY_PREPARED", again)
s, av = call("GET", f"{RES}/api/v1/runs/1/availability?from=0&to=4&class=3A")
check("after charting, availability shows BOOKING_CLOSED", all(p["display"] == "BOOKING_CLOSED" for p in av["pools"]), av)

s, wp2 = call("GET", f"{RES}/api/v1/bookings/{d['pnr']}/wl-prediction", token=D)
# non_null serialisation: an absent estimate is an absent key, not "probability": null
check("after the chart there is no estimate, only the outcome", s == 200 and wp2.get("probability") is None
      and wp2.get("reason") in ("CHART_PREPARED", "NOT_WAITLISTED"), wp2)
s, an = call("GET", f"{RES}/api/v1/staff/runs/1/analytics", token=sm_brc)
gn = next((p for p in an.get("pools", []) if p["classCode"] == "3A" and p["quotaCode"] == "GN"), {})
check("staff analytics: WL funnel matches the chart (1 promoted, 1 expired) and the prediction is counted",
      s == 200 and gn.get("wlPromoted") == 1 and gn.get("wlChartExpired") == 1 and an["predictionsLogged"] == 1, (s, an))
s, _ = call("GET", f"{RES}/api/v1/staff/runs/1/analytics", token=ctl_hwh)
check("analytics outside the staff member's scope -> 403", s == 403, s)

# 8. Chart 2 closes online cancellation
s, ch2 = call("POST", f"{RES}/api/v1/staff/runs/1/charts", {"chartNo": 2}, ctl_brc)
s, err = call("POST", f"{RES}/api/v1/bookings/{b['pnr']}/cancellations", {}, B, {"Idempotency-Key": "lc-" + uuid.uuid4().hex})
check("after chart 2, online cancellation -> 409 RES_CANCELLATION_CLOSED", s == 409 and err["code"] == "RES_CANCELLATION_CLOSED", err)

# 9. Pagination
for _ in range(2):
    sql("SELECT 1")
s, p1 = call("GET", f"{RES}/api/v1/bookings?limit=1", token=A)
check("my bookings: one page with no next cursor for a single booking", s == 200 and len(p1["items"]) == 1 and p1.get("nextCursor") is None, p1)
s, _ = call("GET", f"{RES}/api/v1/bookings?cursor=not-a-cursor", token=A)
check("tampered cursor -> 422, not 500", s == 422, s)

# 10. Audit trail of staff actions queued for the audit chain
check("chart actions emitted to audit.events.v1",
      int(sql("SELECT count(*) FROM reservation.outbox WHERE topic='audit.events.v1' AND payload->>'action' LIKE 'PREPARE_CHART_%'")) >= 2)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
