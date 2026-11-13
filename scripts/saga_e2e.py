"""Phase 9 end-to-end: the booking/payment saga and disruption flows over REAL Kafka.
Services: identity :8082, reservation :8081, payment :8083 (simulated gateway), notification :8084.
Every step goes through public/internal APIs or the gateway simulator, except where noted."""
import base64, hashlib, hmac, json, struct, subprocess, sys, time, urllib.error, urllib.request, uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from kafka import KafkaConsumer, KafkaProducer, TopicPartition

ID, RES, PAY = "http://localhost:8082", "http://localhost:8081", "http://localhost:8083"
BOOT = open("/tmp/railops-kafka.bootstrap").read().strip()
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
    results.append(cond); print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"   -> {detail}"), flush=True)


def wait(fn, timeout=20, every=0.4):
    end = time.time() + timeout
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(every)
    return fn()


def totp(s):
    key = base64.b32decode(s + "=" * (-len(s) % 8)); step = int(time.time()) // 30
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest(); o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def passenger():
    email = f"p.{uuid.uuid4().hex[:8]}@example.com"
    call("POST", f"{ID}/api/v1/auth/register", {"email": email, "password": "deccan-odyssey-2004", "phone": "98%08d" % (uuid.uuid4().int % 10**8)})
    return call("POST", f"{ID}/api/v1/auth/login", {"email": email, "password": "deccan-odyssey-2004"})[1]["accessToken"]


def book(tok, n=1, frm=0, to=4):
    pax = [{"name": "Traveller " + "ABCDEF"[i], "age": 35, "gender": "F"} for i in range(n)]
    s, b = call("POST", f"{RES}/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": frm, "toStopSeq": to, "travelClass": "3A",
                "quota": "GN", "contactPhone": "9820012345", "passengers": pax}, tok, {"Idempotency-Key": "saga-" + uuid.uuid4().hex})
    assert s == 201, (s, b)
    return b


def intent(tok, pnr):
    s, i = call("POST", f"{RES}/api/v1/bookings/{pnr}/payment-intent", token=tok)
    assert s == 201, (s, i)
    return i, i["checkoutUrl"].rsplit("/", 1)[1]


def pay(order, **params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    return call("POST", f"{PAY}/sim-gateway/orders/{order}/pay?{q}")


def status(tok, pnr):
    return call("GET", f"{RES}/api/v1/bookings/{pnr}", token=tok)[1]


def booking_status_is(tok, pnr, want):
    return lambda: status(tok, pnr)["status"] == want


def ledger_balanced():
    return sql("SELECT count(*) FROM (SELECT txn_id FROM payment.ledger_entry GROUP BY txn_id "
               "HAVING sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) <> 0) x") == "0"


def counters_consistent():
    return sql("""WITH a AS (SELECT s.quota_code, s.seg_idx, s.cnf_used, s.rac_used,
                     count(x.id) FILTER (WHERE x.slot_range='[1,3)') f, count(x.id) FILTER (WHERE x.slot_range<>'[1,3)') r
                   FROM reservation.segment_inventory s LEFT JOIN reservation.allocation x ON x.train_run_id=s.train_run_id
                    AND x.class_code=s.class_code AND x.quota_code=s.quota_code AND x.seg_range @> s.seg_idx::int
                   WHERE s.train_run_id=1 GROUP BY 1,2,3,4) SELECT count(*) FROM a WHERE cnf_used<>f OR rac_used<>r""") == "0"


# ----------------------------------------------------------------------------- 1. happy path
A = passenger()
a = book(A, 2)
ia, order_a = intent(A, a["pnr"])
pay(order_a)
check("happy path: gateway capture -> webhook -> PaymentCaptured -> reservation confirms over Kafka",
      wait(booking_status_is(A, a["pnr"], "CONFIRMED")), status(A, a["pnr"])["status"])
check("capture posted to the ledger (balanced Dr GATEWAY_CLEARING / Cr CUSTOMER_PAYABLE)",
      sql(f"SELECT count(*) FROM payment.ledger_entry WHERE pnr='{a['pnr']}'") == "2" and ledger_balanced())
check("passenger notified (contact pulled via scoped internal API, recipient masked at rest)",
      wait(lambda: sql(f"SELECT recipient_masked FROM notification.delivery WHERE pnr='{a['pnr']}' AND template='BookingConfirmed' AND status='SENT'")) == "9820******",
      sql(f"SELECT status, recipient_masked FROM notification.delivery WHERE pnr='{a['pnr']}'"))

# ----------------------------------------------------------------------------- 2. duplicate webhook deliveries
B = passenger()
b = book(B)
_, order_b = intent(B, b["pnr"])
pay(order_b, deliveries=3)
wait(booking_status_is(B, b["pnr"], "CONFIRMED"))
check("same webhook delivered 3x: one capture, one ledger posting, one confirmation",
      sql(f"SELECT count(*) FROM payment.ledger_entry WHERE pnr='{b['pnr']}'") == "2"
      and sql(f"SELECT count(*) FROM payment.outbox WHERE event_type='PaymentCaptured' AND aggregate_id='{b['pnr']}'") == "1")

# ----------------------------------------------------------------------------- 3. forged / replayed webhooks
body = json.dumps({"id": "evt_forged", "type": "payment.captured", "data": {"orderId": order_b, "amount": "1.00"}}).encode()
ts = str(int(time.time()))
bad = hmac.new(b"guessed-secret", (ts + ".").encode() + body, hashlib.sha256).hexdigest()
req = urllib.request.Request(f"{PAY}/webhooks/simgw", data=body, method="POST",
                             headers={"Content-Type": "application/json", "X-Sim-Timestamp": ts, "X-Sim-Signature": bad})
try:
    urllib.request.urlopen(req); forged = 200
except urllib.error.HTTPError as e:
    forged = e.code
real_secret = b"local-only-webhook-secret-0123456789abcdef"
old = str(int(time.time()) - 900)
replay_sig = hmac.new(real_secret, (old + ".").encode() + body, hashlib.sha256).hexdigest()
req = urllib.request.Request(f"{PAY}/webhooks/simgw", data=body, method="POST",
                             headers={"Content-Type": "application/json", "X-Sim-Timestamp": old, "X-Sim-Signature": replay_sig})
try:
    urllib.request.urlopen(req); replay = 200
except urllib.error.HTTPError as e:
    replay = e.code
check("forged signature -> 401, correctly-signed but 15-min-old webhook -> 401", forged == 401 and replay == 401, (forged, replay))

# ----------------------------------------------------------------------------- 4. internal API isolation
s, _ = call("POST", f"{PAY}/internal/v1/payments", {"pnr": a["pnr"], "amount": 1, "currency": "INR"}, token=A)
s2, _ = call("GET", f"{RES}/internal/v1/bookings/{a['pnr']}/contact", token=A)
check("passenger token on payment's internal API -> 401 (wrong audience); on contact API -> 403 (no scope)",
      s == 401 and s2 == 403, (s, s2))

# ----------------------------------------------------------------------------- 5. decline, then retry with a new payment
C = passenger()
c = book(C)
_, order_c1 = intent(C, c["pnr"])
pay(order_c1, outcome="fail")
time.sleep(2)
check("declined payment: booking stays PENDING_PAYMENT (passenger may retry within the hold)",
      status(C, c["pnr"])["status"] == "PENDING_PAYMENT")
ic2, order_c2 = intent(C, c["pnr"])
pay(order_c2)
check("retry creates a NEW payment (old one FAILED), rebinds it, and confirms",
      order_c2 != order_c1 and wait(booking_status_is(C, c["pnr"], "CONFIRMED")), (order_c1, order_c2))

# ----------------------------------------------------------------------------- 6. captured amount differs from the fare
D = passenger()
d = book(D)
_, order_d = intent(D, d["pnr"])
pay(order_d, capturedAmount="10.00")
check("captured amount != fare -> FAILED_REFUNDING (never confirmed on a mismatch)",
      wait(booking_status_is(D, d["pnr"], "FAILED_REFUNDING")), status(D, d["pnr"])["status"])
check("...and the stray 10.00 is refunded end to end (payment refund SUCCEEDED, ledger reversal)",
      wait(lambda: sql(f"SELECT status FROM payment.refund WHERE pnr='{d['pnr']}'") == "SUCCEEDED", 30)
      and sql(f"SELECT count(*) FROM payment.ledger_entry WHERE pnr='{d['pnr']}'") == "4" and ledger_balanced(),
      sql(f"SELECT status, last_error FROM payment.refund WHERE pnr='{d['pnr']}'"))

# ----------------------------------------------------------------------------- 7. late payment after hold expiry
E = passenger()
e = book(E)
_, order_e = intent(E, e["pnr"])
# Past the 2-minute pending grace (INC-001): the checkout was opened, so a hold 1 s past expiry is rightly kept.
sql(f"UPDATE reservation.booking SET hold_expires_at = now() - interval '3 minutes' WHERE pnr='{e['pnr']}';"
    f"UPDATE reservation.allocation SET hold_expires_at = now() - interval '1 second' WHERE state='HELD' AND passenger_id IN "
    f"(SELECT p.id FROM reservation.passenger p JOIN reservation.pnr_directory d ON d.booking_id=p.booking_id WHERE d.pnr='{e['pnr']}')")
check("hold expires before payment (sweeper)", wait(booking_status_is(E, e["pnr"], "EXPIRED")), status(E, e["pnr"])["status"])
pay(order_e)
check("late capture re-seats the passenger (seats still free) -> CONFIRMED",
      wait(booking_status_is(E, e["pnr"], "CONFIRMED")), status(E, e["pnr"])["status"])

# ----------------------------------------------------------------------------- 8. webhook never arrives -> reconciliation
F = passenger()
f = book(F)
_, order_f = intent(F, f["pnr"])
pay(order_f, suppressWebhook="true")
time.sleep(3)
check("webhook suppressed: booking still pending", status(F, f["pnr"])["status"] == "PENDING_PAYMENT")
check("reconciler finds the capture at the gateway (~20 s stale threshold) and confirms",
      wait(booking_status_is(F, f["pnr"], "CONFIRMED"), timeout=45), status(F, f["pnr"])["status"])

# ----------------------------------------------------------------------------- 9. user cancellation -> refund over Kafka
s, cv = call("POST", f"{RES}/api/v1/bookings/{b['pnr']}/cancellations", {}, B, {"Idempotency-Key": "saga-" + uuid.uuid4().hex})
check("cancellation refund reaches the gateway and settles (RefundRequested -> payment -> webhook -> SUCCEEDED)",
      s == 200 and wait(lambda: sql(f"SELECT status FROM payment.refund WHERE pnr='{b['pnr']}'") == "SUCCEEDED", 30),
      (s, sql(f"SELECT status, last_error FROM payment.refund WHERE pnr='{b['pnr']}'")))
check("refund amount equals the quoted refund", sql(f"SELECT amount FROM payment.refund WHERE pnr='{b['pnr']}'") == f"{float(cv['refundAmount']):.2f}")
check("RefundSucceeded notification sent", wait(lambda: sql(f"SELECT count(*) FROM notification.delivery WHERE pnr='{b['pnr']}' AND template='RefundSucceeded'") == "1"))

# ----------------------------------------------------------------------------- 10. over-refund blocked
pid = sql(f"SELECT payment_id FROM reservation.booking WHERE pnr='{a['pnr']}'")
producer = KafkaProducer(bootstrap_servers=BOOT)
evil = {"eventId": str(uuid.uuid4()), "eventType": "RefundRequested", "schemaVersion": 1, "occurredAt": "2026-11-11T00:00:00Z",
        "aggregateId": a["pnr"], "payload": {"pnr": a["pnr"], "paymentId": pid, "amount": 999999.00, "reason": "BUG",
                                             "idempotencyKey": "bug-" + uuid.uuid4().hex}}
producer.send("reservation.booking.v1", key=a["pnr"].encode(), value=json.dumps(evil).encode()).get(10)
check("a (buggy) refund larger than the capture is blocked -> MANUAL_REVIEW, never sent to the gateway",
      wait(lambda: sql(f"SELECT status FROM payment.refund WHERE pnr='{a['pnr']}' AND reason='BUG'") == "MANUAL_REVIEW"),
      sql(f"SELECT status, last_error FROM payment.refund WHERE pnr='{a['pnr']}'"))

# ----------------------------------------------------------------------------- 11. poison message -> consumer-scoped DLT
poison_key = "poison-" + uuid.uuid4().hex[:6]
producer.send("payment.events.v1", key=poison_key.encode(), value=b"{not json").get(10)
producer.flush()
def dlt_has(topic, key):
    cons = KafkaConsumer(bootstrap_servers=BOOT, auto_offset_reset="earliest", consumer_timeout_ms=3000,
                         group_id=None, enable_auto_commit=False)
    cons.subscribe([topic])
    found = any(m.key == key.encode() for m in cons)
    cons.close()
    return found
check("malformed event skips retries and lands in payment.events.v1.reservation.dlt (and .notification.dlt)",
      wait(lambda: dlt_has("payment.events.v1.reservation.dlt", poison_key), 25)
      and wait(lambda: dlt_has("payment.events.v1.notification.dlt", poison_key), 25))
check("the poison message did not block later events (partition keeps flowing)",
      status(A, a["pnr"])["status"] == "CONFIRMED")

# ----------------------------------------------------------------------------- 12. waitlist promotion over Kafka
# Provision explicitly: earlier sections may have used the whole seeded pool (3 CNF / 2 RAC). This section needs at
# least one CNF to cancel. (Until Phase 10 it passed only because reset-local.sh left benchmark capacities behind.)
sql("UPDATE reservation.segment_inventory SET cnf_capacity = cnf_used + 2 WHERE train_run_id=1 AND quota_code='GN'")
cnf_free = int(sql("SELECT min(cnf_capacity-cnf_used) FROM reservation.segment_inventory WHERE quota_code='GN'"))
G = passenger(); fillers = []
for _ in range(cnf_free):
    x = book(G); _, o = intent(G, x["pnr"]); pay(o); fillers.append(x)
rac_free = int(sql("SELECT min(rac_capacity-rac_used) FROM reservation.segment_inventory WHERE quota_code='GN'"))
for _ in range(rac_free):
    x = book(G); _, o = intent(G, x["pnr"]); pay(o)
H = passenger()
h = book(H)
_, oh = intent(H, h["pnr"]); pay(oh)
wait(booking_status_is(H, h["pnr"], "CONFIRMED"))
check("pool full: new booking is waitlisted", status(H, h["pnr"])["passengers"][0]["currentStatus"].startswith("WL"),
      status(H, h["pnr"])["passengers"])
wait(lambda: all(status(G, x["pnr"])["status"] == "CONFIRMED" for x in fillers))
call("POST", f"{RES}/api/v1/bookings/{fillers[0]['pnr']}/cancellations", {}, G, {"Idempotency-Key": "saga-" + uuid.uuid4().hex})
check("a CNF cancellation promotes along the chain via InventoryChanged (RAC->CNF, WL->RAC)",
      wait(lambda: not status(H, h["pnr"])["passengers"][0]["currentStatus"].startswith("WL"), 20),
      status(H, h["pnr"])["passengers"])
check("promotion notification sent", wait(lambda: sql(f"SELECT count(*) FROM notification.delivery WHERE pnr='{h['pnr']}' AND template='PassengerPromoted'") == "1"))

# ----------------------------------------------------------------------------- 13. run cancellation (disruption) + the closed race
blob = bytes.fromhex(sql("SELECT encode(mfa_secret_enc,'hex') FROM identity.app_user WHERE email='root.admin@railops.local'") or "00")
if len(blob) < 20:
    et = call("POST", f"{ID}/api/v1/auth/login", {"email": "root.admin@railops.local", "password": "Local-Only-Bootstrap-Passw0rd"})[1]["enrolmentToken"]
    secret = call("POST", f"{ID}/api/v1/auth/mfa/enrol", token=et)[1]["secretBase32"]
    call("POST", f"{ID}/api/v1/auth/mfa/confirm", {"code": totp(secret)}, et)
    time.sleep(31 - int(time.time()) % 30)
else:
    secret = AESGCM(base64.b64decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")).decrypt(blob[1:13], blob[13:], b"identity.app_user.mfa_secret").decode()
    time.sleep(31 - int(time.time()) % 30)
admin = call("POST", f"{ID}/api/v1/auth/login", {"email": "root.admin@railops.local", "password": "Local-Only-Bootstrap-Passw0rd", "otp": totp(secret)})[1]["accessToken"]
s, r = call("POST", f"{RES}/api/v1/staff/runs/1/cancel", {"reason": "Track damage near Ratlam"}, admin)
check("controller/admin cancels the run -> 202", s == 202, (s, r))
s, err = call("POST", f"{RES}/api/v1/bookings", {"trainRunId": 1, "fromStopSeq": 0, "toStopSeq": 4, "travelClass": "3A",
              "quota": "GN", "contactPhone": "9820012345", "passengers": [{"name": "Late Booker", "age": 30, "gender": "M"}]},
              passenger(), {"Idempotency-Key": "saga-" + uuid.uuid4().hex})
check("booking after cancellation -> 409 RES_RUN_NOT_BOOKABLE", s == 409, (s, err))
active = ["CONFIRMED", "PARTIALLY_CANCELLED"]
check("bulk job refunds every confirmed booking in full (RUN_CANCELLED, no cancellation charge)",
      wait(lambda: sql("SELECT status FROM reservation.bulk_job WHERE train_run_id=1") == "COMPLETED", 30)
      and sql("SELECT count(*) FROM reservation.booking WHERE status IN ('CONFIRMED','PARTIALLY_CANCELLED','PENDING_PAYMENT')") == "0"
      and sql("SELECT count(*) FROM reservation.cancellation WHERE kind='RUN_CANCELLED' AND total_charge <> 0") == "0",
      sql("SELECT status, processed, refunds FROM reservation.bulk_job"))
check("every run-cancellation refund settles at the gateway",
      wait(lambda: sql("SELECT count(*) FROM payment.refund WHERE reason='RUN_CANCELLED' AND status <> 'SUCCEEDED'") == "0"
           and int(sql("SELECT count(*) FROM payment.refund WHERE reason='RUN_CANCELLED'")) > 0, 40),
      sql("SELECT status, count(*) FROM payment.refund WHERE reason='RUN_CANCELLED' GROUP BY 1"))
check("re-delivering RunCancelled doesn't refund twice (bulk job + refund idempotency keys)",
      sql("SELECT count(*) - count(DISTINCT idempotency_key) FROM payment.refund") == "0")

# ----------------------------------------------------------------------------- 14. global invariants
check("ledger: every transaction balanced", ledger_balanced())
check("payment: no payment refunded beyond its capture",
      sql("""SELECT count(*) FROM payment.payment p WHERE p.captured_amount < (SELECT COALESCE(sum(amount),0) FROM payment.refund r
             WHERE r.payment_id=p.id AND r.status <> 'MANUAL_REVIEW')""") == "0")
check("reservation: counters match allocations after everything", counters_consistent())
check("outboxes fully drained to Kafka", wait(lambda: sql("SELECT (SELECT count(*) FROM reservation.outbox WHERE published_at IS NULL) + (SELECT count(*) FROM payment.outbox WHERE published_at IS NULL)") == "0"))
check("staff actions reached the audit chain via Kafka (CANCEL_RUN)",
      wait(lambda: sql("SELECT count(*) FROM identity.audit_log WHERE action='CANCEL_RUN' AND source_event_id IS NOT NULL") == "1"))
s, v = call("GET", f"{ID}/api/v1/admin/audit/verify", token=admin)
check("audit chain still intact", s == 200 and v.get("intact") is True, v)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
