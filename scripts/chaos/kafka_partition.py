"""DRILL: the Kafka cluster becomes unreachable for PARTITION_S seconds while customers keep booking and paying.

Hypothesis before running: bookings keep working (outbox), payments capture (HTTP webhook), nothing is lost, and
everything converges when the network heals. What could go wrong: a paid customer's hold expires while the
PaymentCaptured event is stuck in payment-service's outbox.

Requires the local stack started with a 1-minute hold:  RAILOPS_BOOKING_HOLDMINUTES=1 scripts/start-local.sh --fresh
"""
import os, sys, time, threading
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

PARTITION_S = int(os.environ.get("PARTITION_S", "100"))
N = int(os.environ.get("CUSTOMERS", "4"))
port = kafka_port()

CONTENTION = os.environ.get("CONTENTION") == "1"
# Contention variant: exactly enough confirmed berths for the paid customers; WL closed, so a freed berth is the
# only way anyone else gets in.
sql("UPDATE reservation.segment_inventory SET cnf_capacity = cnf_used + " + str(N + 2 if CONTENTION else 20)
    + ", rac_capacity = 0 WHERE train_run_id=1 AND quota_code='GN'")
sql("UPDATE reservation.waitlist_counter SET wl_cap = next_seq - 1 WHERE train_run_id=1 AND quota_code='GN'") if CONTENTION else None
tokens = [passenger() for _ in range(N + 1)]
rivals = [passenger("rival") for _ in range(N)] if CONTENTION else []

# ---- baseline: the happy path works before the fault
st, b, _ = book(tokens[0]); _, i, _ = intent(tokens[0], b["pnr"]); customer_pays(i["checkoutUrl"])
for _ in range(30):
    if booking(tokens[0], b["pnr"]).get("status") == "CONFIRMED": break
    time.sleep(0.5)
log(f"baseline booking {b['pnr']}: {booking(tokens[0], b['pnr']).get('status')}")
before_unpub = sql("SELECT count(*) FROM reservation.outbox WHERE published_at IS NULL") + "/" + \
               sql("SELECT count(*) FROM payment.outbox WHERE published_at IS NULL")

# ---- fault
iptables("block", port)
log(f"FAULT: Kafka port {port} partitioned (packets dropped). Unpublished outbox rows res/pay before: {before_unpub}")

paid = []
for k, tok in enumerate(tokens[1:], 1):
    st, b, dt = book(tok, name=f"Paid Customer {chr(64 + k)}")
    if st != 201:
        log(f"  booking failed during partition: {st} {b}"); continue
    st2, i, dt2 = intent(tok, b["pnr"])
    st3, p, dt3 = customer_pays(i["checkoutUrl"]) if st2 == 201 else (st2, i, 0)
    paid.append((tok, b["pnr"]))
    log(f"  customer {k}: book {st} ({dt*1000:.0f} ms), intent {st2} ({dt2*1000:.0f} ms), pay {st3}  PNR {b['pnr']}")

# CONTROL (INC-001b): an abandoned checkout during the partition must still expire. Without this, a sweeper that
# silently stopped working looks exactly like a sweeper that correctly protected the paid holds.
control_tok = passenger("control")
cst, control, _ = book(control_tok, name="Abandoned Checkout")
intent(control_tok, control["pnr"])          # payment intent created, customer never pays
log(f"  control: unpaid hold {control['pnr']} (must EXPIRE)")
time.sleep(5)
cap = sql("SELECT count(*) FROM payment.payment WHERE status='CAPTURED'")
log(f"payments CAPTURED in payment DB: {cap}; bookings still PENDING_PAYMENT: "
    + sql("SELECT count(*) FROM reservation.booking WHERE status='PENDING_PAYMENT'"))
log("health during partition: " + ", ".join(f"{s}={call('GET', f'http://localhost:{p}/actuator/health/readiness')[0]}"
                                          for s, p in [("res", 8081), ("pay", 8083), ("notif", 8084)]))

deadline = time.time() + PARTITION_S
rivals_done = False
while time.time() < deadline:
    if CONTENTION and not rivals_done and sql("SELECT count(*) FROM reservation.booking WHERE status='EXPIRED'") != "0":
        got = [book(t, name="Rival Customer")[0] for t in rivals]
        log(f"  rivals book the freed berths: {got}")
        rivals_done = True
    time.sleep(10)
    log(f"  alert signal outbox.oldest.age res/pay: {metric(8081, 'outbox_oldest_age_seconds')}/{metric(8083, 'outbox_oldest_age_seconds')} s"
        f" | holds rescued: {metric(8081, 'booking_hold_rescued_total')}")
    log("  outbox unpublished res/pay: " + sql("SELECT count(*) FROM reservation.outbox WHERE published_at IS NULL") + "/"
        + sql("SELECT count(*) FROM payment.outbox WHERE published_at IS NULL")
        + "  | booking statuses: " + sql("SELECT string_agg(status||':'||n, ' ') FROM (SELECT status, count(*) n FROM reservation.booking GROUP BY 1) x"))

# ---- heal
iptables("unblock", port)
healed = time.time()
log("HEAL: partition removed")
drained_at = None
for _ in range(120):
    left = int(sql("SELECT (SELECT count(*) FROM reservation.outbox WHERE published_at IS NULL) + "
                   "(SELECT count(*) FROM payment.outbox WHERE published_at IS NULL)"))
    if left == 0 and drained_at is None:
        drained_at = time.time(); log(f"outboxes drained {drained_at - healed:.1f}s after heal")
    if drained_at and time.time() - drained_at > 15: break
    time.sleep(1)

# ---- outcome per paid customer
log("OUTCOME for customers who PAID during the partition:")
lost_seat = 0
for tok, pnr in paid:
    v = booking(tok, pnr)
    refund = sql(f"SELECT coalesce(string_agg(status||' '||amount, ','),'-') FROM payment.refund r JOIN payment.payment p ON p.id=r.payment_id WHERE p.pnr='{pnr}'")
    reason = sql(f"SELECT coalesce(string_agg(payload->>'reason', ','),'-') FROM reservation.outbox WHERE event_type='RefundRequested' AND payload->>'pnr'='{pnr}'")
    seat = v.get("status") == "CONFIRMED"
    lost_seat += not seat
    log(f"  {pnr}: booking {v.get('status')}, passenger {v.get('passengers',[{}])[0].get('currentStatus')}, refund {refund} ({reason})")

# The control opened a checkout (payment PENDING at the PSP), so it gets the 2-minute pending grace after its hold.
for _ in range(120):
    control_status = booking(control_tok, control["pnr"]).get("status")
    if control_status != "PENDING_PAYMENT": break
    time.sleep(1)
log(f"  CONTROL {control['pnr']}: {control_status} (expected EXPIRED) | rescued={metric(8081, 'booking_hold_rescued_total')} "
    f"scheduler_failures={metric(8081, 'scheduled_task_failures_total')}")
log(f"SUMMARY: paid={len(paid)} lost_seat={lost_seat} control_expired={control_status == 'EXPIRED'} double_booked={double_booked()} ledger_balanced={ledger_balanced()} "
    f"unpublished={sql('SELECT count(*) FROM reservation.outbox WHERE published_at IS NULL')}+{sql('SELECT count(*) FROM payment.outbox WHERE published_at IS NULL')}")
open("/tmp/drill-kafka.timeline", "w").write("\n".join(TIMELINE))
