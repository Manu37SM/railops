"""DRILL: a bad producer deploy publishes a malformed PaymentCaptured (amount "2,915.00") AHEAD of a real payment
for the same PNR, on the same partition.

Questions: is the poison quarantined (DLT) or does it block the partition (head-of-line)? Is anyone told? Can
on-call see why, and replay it safely?
"""
import datetime, json, os, subprocess, sys, time, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *
from kafka import KafkaProducer

TOPIC, DLT = "payment.events.v1", "payment.events.v1.reservation.dlt"
DLT_TOOL = [sys.executable, "-I", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ops", "dlt.py")]

tok = passenger()
st, b, _ = book(tok, name="Poison Drill")
pnr = b["pnr"]
_, i, _ = intent(tok, pnr)
payment_id = sql(f"SELECT payment_id FROM reservation.booking WHERE pnr = '{pnr}'")
log(f"booking {pnr} held, payment {payment_id} PENDING at the PSP")

poison_id = str(uuid.uuid4())
env = {"eventId": poison_id, "eventType": "PaymentCaptured", "schemaVersion": 1,
       "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "aggregateId": pnr,
       "payload": {"paymentId": payment_id, "pnr": pnr, "amount": "2,915.00", "currency": "INR"}}
p = KafkaProducer(bootstrap_servers=open("/tmp/railops-kafka.bootstrap").read().strip(), acks="all")
p.send(TOPIC, key=pnr.encode(), value=json.dumps(env).encode(),
       headers=[("eventId", poison_id.encode()), ("eventType", b"PaymentCaptured")]).get(timeout=10)
p.flush()
log(f"FAULT: malformed PaymentCaptured (amount \"2,915.00\") published to {TOPIC}, key {pnr}")

customer_pays(i["checkoutUrl"])
log("the customer pays for real: the genuine PaymentCaptured lands BEHIND the poison on the same partition")
t = time.time()
for _ in range(60):
    if booking(tok, pnr).get("status") == "CONFIRMED":
        break
    time.sleep(0.5)
log(f"booking {booking(tok, pnr).get('status')} {time.time() - t:.1f}s after payment (head-of-line blocked? "
    f"{'NO' if booking(tok, pnr).get('status') == 'CONFIRMED' else 'YES'})")
time.sleep(2)
log(f"alert signal kafka_dead_lettered_total = {metric(8081, 'kafka_dead_lettered_total')}")

log("TRIAGE: scripts/ops/dlt.py list " + DLT)
out = subprocess.run(DLT_TOOL + ["list", DLT], capture_output=True, text=True).stdout
for line in out.splitlines():
    log("  " + line[:260])
mine = [json.loads(l) for l in out.splitlines()[1:] if json.loads(l)["eventId"] == poison_id]
log(f"  this drill's record: {mine[0]['at']} (others in the DLT are older incidents: never pick by position)")

log("REPLAY attempt 1: patch the amount WITHOUT --allow-money-fields")
r = subprocess.run(DLT_TOOL + ["replay", DLT, "--event-id", poison_id, "--patch", '{"amount": 2915.00}', "--yes",
                               "--operator", "drill"], capture_output=True, text=True)
log("  " + (r.stderr or r.stdout).strip().splitlines()[-1])
log("REPLAY attempt 2: with --allow-money-fields --yes (second reviewer approved)")
r = subprocess.run(DLT_TOOL + ["replay", DLT, "--event-id", poison_id, "--patch", '{"amount": 2915.00}',
                               "--allow-money-fields", "--yes", "--operator", "drill"], capture_output=True, text=True)
for line in r.stdout.strip().splitlines():
    log("  " + line[:260])
time.sleep(4)
confirmed = sql(f"SELECT count(*) FROM reservation.outbox WHERE event_type='BookingConfirmed' AND payload->>'pnr'='{pnr}'")
refunds = sql(f"SELECT count(*) FROM reservation.outbox WHERE event_type='RefundRequested' AND payload->>'pnr'='{pnr}'")
processed = sql(f"SELECT count(*) FROM reservation.processed_event WHERE event_id = '{poison_id}'")
log(f"after replay: booking {booking(tok, pnr).get('status')}, BookingConfirmed events={confirmed}, "
    f"RefundRequested={refunds}, replayed eventId processed={processed} (idempotent: same payment, already confirmed)")
open("/tmp/drill-poison.timeline", "w").write("\n".join(TIMELINE))
