"""Dead-letter topic triage and replay (INC-003).

  dlt.py list   <dlt-topic>                          what is dead-lettered, why, how old
  dlt.py replay <dlt-topic> (--event-id UUID | --offset P:N) [--patch '{"amount": 2915.00}'] [--allow-money-fields] [--yes]

Replay re-publishes the record to its ORIGINAL topic, with the same key (same partition, so per-PNR ordering holds)
and the same eventId. Consumers deduplicate on eventId, so replaying something that was meanwhile handled another
way is a no-op, and replaying twice is safe. Dry-run unless --yes. Patching a money field (amount, fare, refund,
price) needs --allow-money-fields: hand-editing money in an incident needs a second pair of eyes, not a default.

Bootstrap: $KAFKA_BOOTSTRAP or /tmp/railops-kafka.bootstrap. Audit: every replay prints one JSON line (pipe to the
incident channel / ticket).
"""
import argparse, datetime, getpass, json, os, re, sys

from kafka import KafkaConsumer, KafkaProducer, TopicPartition

MONEY = re.compile(r"amount|fare|refund|price", re.I)
DLT_HEADERS = "kafka_dlt-"


def bootstrap():
    return os.environ.get("KAFKA_BOOTSTRAP") or open("/tmp/railops-kafka.bootstrap").read().strip()


def read_all(topic):
    c = KafkaConsumer(bootstrap_servers=bootstrap(), enable_auto_commit=False, consumer_timeout_ms=3000,
                      auto_offset_reset="earliest", group_id=None)
    parts = [TopicPartition(topic, p) for p in (c.partitions_for_topic(topic) or [])]
    if not parts:
        sys.exit(f"no such topic: {topic}")
    c.assign(parts)
    c.seek_to_beginning(*parts)
    recs = list(c)
    c.close()
    return recs


def hdr(rec):
    return {k: v for k, v in (rec.headers or [])}


def describe(rec):
    h = hdr(rec)
    env = json.loads(rec.value)
    age = datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromtimestamp(rec.timestamp / 1000, datetime.timezone.utc)
    return {
        "at": f"{rec.partition}:{rec.offset}",
        "age": str(age).split(".")[0],
        "key": rec.key.decode() if rec.key else None,
        "eventType": env.get("eventType"),
        "eventId": env.get("eventId"),
        "origin": f"{h.get('kafka_dlt-original-topic', b'?').decode()}"
                  f"@{int.from_bytes(h.get('kafka_dlt-original-partition', b'\x00'), 'big')}"
                  f":{int.from_bytes(h.get('kafka_dlt-original-offset', b'\x00'), 'big')}",
        "error": (h.get("kafka_dlt-exception-cause-fqcn") or h.get("kafka_dlt-exception-fqcn", b"")).decode().rsplit(".", 1)[-1],
        "message": h.get("kafka_dlt-exception-message", b"").decode()[:240],
    }


def cmd_list(a):
    recs = sorted(read_all(a.topic), key=lambda r: r.timestamp)       # oldest first, across partitions
    print(f"{len(recs)} dead-lettered record(s) in {a.topic}")
    for r in recs:
        print(json.dumps(describe(r), ensure_ascii=False))


def cmd_replay(a):
    # Prefer --event-id: positions are easy to mistype and DLTs keep old records (INC-003 drill replayed a stale one).
    recs = read_all(a.topic)
    if a.event_id:
        matches = [r for r in recs if json.loads(r.value).get("eventId") == a.event_id]
        if len(matches) != 1:
            sys.exit(f"{len(matches)} records with eventId {a.event_id} in {a.topic}: refusing to guess")
        rec = matches[0]
    else:
        part, off = (int(x) for x in a.offset.split(":"))
        rec = next((r for r in recs if r.partition == part and r.offset == off), None)
        if rec is None:
            sys.exit(f"no record at {a.offset} in {a.topic}")
    part, off = rec.partition, rec.offset
    h = hdr(rec)
    origin = h["kafka_dlt-original-topic"].decode()
    env = json.loads(rec.value)
    patch = json.loads(a.patch) if a.patch else {}
    money = [k for k in patch if MONEY.search(k)]
    if money and not a.allow_money_fields:
        sys.exit(f"refusing to patch money field(s) {money} without --allow-money-fields (get a second reviewer)")
    before = dict(env["payload"])
    env["payload"].update(patch)
    out_headers = [(k, v) for k, v in rec.headers if not k.startswith(DLT_HEADERS)]
    out_headers.append(("replayed-from", f"{a.topic}@{part}:{off}".encode()))
    out_headers.append(("replayed-by", (a.operator or getpass.getuser()).encode()))
    audit = {"action": "dlt-replay", "dlt": f"{a.topic}@{part}:{off}", "to": origin, "key": rec.key.decode() if rec.key else None,
             "eventId": env.get("eventId"), "eventType": env.get("eventType"),
             "patch": {k: {"from": before.get(k), "to": v} for k, v in patch.items()},
             "operator": a.operator or getpass.getuser(), "dryRun": not a.yes,
             "at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    print(json.dumps(audit, ensure_ascii=False))
    if not a.yes:
        print("dry run: nothing published (add --yes)")
        return
    p = KafkaProducer(bootstrap_servers=bootstrap(), acks="all")
    p.send(origin, key=rec.key, value=json.dumps(env).encode(), headers=out_headers).get(timeout=10)
    p.flush()
    print(f"replayed to {origin} (same key, same eventId)")


ap = argparse.ArgumentParser()
sub = ap.add_subparsers(dest="cmd", required=True)
l = sub.add_parser("list"); l.add_argument("topic"); l.set_defaults(fn=cmd_list)
r = sub.add_parser("replay"); r.add_argument("topic")
sel = r.add_mutually_exclusive_group(required=True); sel.add_argument("--event-id"); sel.add_argument("--offset")
r.add_argument("--patch"); r.add_argument("--allow-money-fields", action="store_true")
r.add_argument("--operator"); r.add_argument("--yes", action="store_true"); r.set_defaults(fn=cmd_replay)
a = ap.parse_args()
a.fn(a)
