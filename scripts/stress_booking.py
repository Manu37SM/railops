"""Fires concurrent bookings at the local reservation-service and tallies outcomes.

Both quotas (GN, TQ) draw from the same physical berths, so allocators for different quotas race
for identical berths: exactly the case the exclusion constraint + ON CONFLICT DO NOTHING handles.
"""
import json
import random
import sys
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

URL = "http://localhost:8081/api/v1/bookings"
ID = "http://localhost:8082/api/v1/auth"
N = int(sys.argv[1]) if len(sys.argv) > 1 else 300
WORKERS = int(sys.argv[2]) if len(sys.argv) > 2 else 48
random.seed(7)


def _post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


# Phase 6: real JWTs. A pool of passengers (Argon2 makes logins deliberately expensive, so log in once each).
TOKENS = []
for k in range(8):
    email = f"stress.{uuid.uuid4().hex[:8]}@example.com"
    _post(ID + "/register", {"email": email, "password": "rajdhani-express-" + str(k), "phone": "98200%05d" % k})
    TOKENS.append(_post(ID + "/login", {"email": email, "password": "rajdhani-express-" + str(k)})["accessToken"])


def one(i):
    frm = random.randint(0, 3)
    to = random.randint(frm + 1, 4)
    pax = [{"name": "Passenger " + "ABCDEFGHIJ"[i % 10] + "abc"[k], "age": random.choice([25, 40, 63]), "gender": random.choice("MF")}
           for k in range(random.randint(1, 3))]
    body = {"trainRunId": 1, "fromStopSeq": frm, "toStopSeq": to, "travelClass": "3A",
            "quota": random.choice(["GN", "TQ"]), "contactPhone": "9820000000", "passengers": pax}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST", headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + TOKENS[i % len(TOKENS)],
        "Idempotency-Key": "stress-" + uuid.uuid4().hex})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read())
            return ["OK"] + [p["bookingStatus"].split("/")[0] for p in data["passengers"]]
    except urllib.error.HTTPError as e:
        return [json.loads(e.read()).get("code", str(e.code))]


with ThreadPoolExecutor(WORKERS) as ex:
    results = list(ex.map(one, range(N)))

requests = Counter(r[0] for r in results)
passengers = Counter(s for r in results if r[0] == "OK" for s in r[1:])
print("requests:  ", dict(requests))
print("passengers:", dict(passengers))
