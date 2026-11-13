"""Synthetic booking history with ground-truth waitlist outcomes.

WHY SYNTHETIC: no real reservation history exists in this project. The simulator encodes the mechanics that drive
waitlist confirmation on Indian Railways (60-day advance reservation period, festival/summer peaks, weekday effects,
cancellations that accelerate towards departure, FIFO promotion, unused-Tatkal release at chart) so the PIPELINE
(point-in-time features, time-based evaluation, calibration, serving parity, monitoring) can be built and tested.
Accuracy measured on this data says NOTHING about accuracy on real traffic: re-train and re-evaluate on real data
before showing any number to a passenger.

The model never sees the latent parameters below (popularity, season multiplier, cancellation propensity). It sees
only what a booking system can observe at booking time (see features.py).

Output: one row per WAITLISTED booking, with a snapshot of observable state at the moment it was made, and the label
`confirmed` = the party had berths when the chart was prepared.
"""
from __future__ import annotations

import dataclasses
import datetime as dt
import math

import numpy as np
import pandas as pd

ARP_DAYS = 60
CLASSES = {"3A": 64, "SL": 72}          # berths per pool in the simulation (one coach each, GN quota)
TATKAL_SHARE = 0.15                       # of physical berths held for Tatkal; unused ones go to GN WL at chart
WL_CAP = 1.0                              # WL closes at WL_CAP x capacity


def festival_factor(d: dt.date) -> float:
    """Latent demand multiplier. The model sees only a public calendar flag (features.FESTIVALS), not this curve."""
    m, day = d.month, d.day
    f = 1.0
    if m in (5, 6):                                       # summer holidays
        f *= 1.35
    if (m == 10 and day >= 15) or (m == 11 and day <= 10):  # Dussehra/Diwali/Chhath travel peak
        f *= 1.8
    if (m == 3 and 10 <= day <= 20):                       # Holi
        f *= 1.4
    if (m == 12 and day >= 20) or (m == 1 and day <= 3):   # year end
        f *= 1.3
    return f


@dataclasses.dataclass
class Train:
    number: str
    popularity: float     # latent
    cancel_prop: float    # latent base cancellation propensity


def make_trains(rng: np.random.Generator, n: int) -> list[Train]:
    return [Train(number=f"1{2001 + 7 * i:04d}", popularity=float(rng.lognormal(0.0, 0.35)),
                  cancel_prop=float(rng.uniform(0.12, 0.30))) for i in range(n)]


def simulate_pool(rng, train: Train, run_date: dt.date, cls: str, capacity: int) -> list[dict]:
    weekday_f = 1.25 if run_date.weekday() in (4, 6) else (0.85 if run_date.weekday() in (1, 2) else 1.0)
    season = festival_factor(run_date)
    tatkal = int(round(capacity * TATKAL_SHARE))
    gn_cap = capacity - tatkal
    expected = gn_cap * train.popularity * season * weekday_f * 1.15
    n_req = int(rng.poisson(expected))
    if n_req == 0:
        return []
    # arrival time (days before departure): mass at ARP opening + build-up near departure
    early = rng.random(n_req) < 0.35
    t = np.where(early, rng.uniform(52, ARP_DAYS, n_req), rng.beta(1.2, 3.0, n_req) * ARP_DAYS)
    t = np.sort(t)[::-1]                                   # process in time order (days-before decreasing)
    party = rng.choice([1, 1, 1, 2, 2, 3, 4], size=n_req)

    free = gn_cap
    wl: list[int] = []                                     # booking ids in WL order
    seats: dict[int, int] = {}                             # booking id -> berths held
    status: dict[int, str] = {}
    cancel_at: dict[int, float] = {}
    events = []                                            # (days_before, kind, id)
    rows = []
    bookings_times: list[float] = []
    cancels_so_far = 0
    wl_cap_people = int(WL_CAP * capacity)
    wl_people = 0

    def promote():
        nonlocal free, wl_people
        while wl and free >= seats[wl[0]]:
            b = wl.pop(0)
            free -= seats[b]
            wl_people -= seats[b]
            status[b] = "CNF"

    # cancellation propensity rises in festival season? lower (people really travel): latent
    cancel_p = train.cancel_prop * (0.75 if season > 1.3 else 1.0)
    pending_cancels: list[tuple[float, int]] = []
    for i in range(n_req):
        now = float(t[i])
        # apply cancellations that happened before this request
        pending_cancels.sort(reverse=True)
        while pending_cancels and pending_cancels[0][0] >= now:
            _, b = pending_cancels.pop(0)
            if status.get(b) == "CNF":
                free += seats[b]
            elif status.get(b) == "WL":
                wl.remove(b)
                wl_people -= seats[b]
            status[b] = "CAN"
            cancels_so_far += 1
            promote()
        k = int(party[i])
        recent = sum(1 for bt in bookings_times if bt - now <= 7)
        bookings_times.append(now)
        if free >= k:
            free -= k
            status[i] = "CNF"
        elif wl_people + k <= wl_cap_people:
            status[i] = "WL"
            wl.append(i)
            wl_people += k
            rows.append(dict(id=i, train=train.number, run_date=run_date, cls=cls, quota="GN",
                             days_before=now, party=k, capacity=capacity,
                             wl_position=wl_people,              # passenger-level WL number of the party's last member
                             bookings_last7=recent, cancels_so_far=cancels_so_far))
        else:
            continue                                     # regret: no booking
        seats[i] = k
        # will this booking cancel, and when? early bookers cancel more; cancellations pile up near departure
        p = cancel_p * (1.25 if now > 30 else 1.0) * (1.4 if status[i] == "WL" else 1.0)
        if rng.random() < min(0.9, p):
            pending_cancels.append((float(now * rng.beta(1.0, 2.2)), i))
    # remaining cancellations up to chart time (4 h before departure ~ 0.17 days)
    pending_cancels.sort(reverse=True)
    for when, b in pending_cancels:
        if when < 0.17:
            continue
        if status.get(b) == "CNF":
            free += seats[b]
        elif status.get(b) == "WL":
            wl.remove(b)
            wl_people -= seats[b]
        status[b] = "CAN"
        promote()
    # chart: unused Tatkal berths released to GN waitlist
    tatkal_unused = int(rng.binomial(tatkal, max(0.0, 0.35 - 0.25 * (season - 1))))
    free += tatkal_unused
    promote()
    out = []
    for r in rows:
        if status[r["id"]] == "CAN":
            continue                                     # cancelled WL bookings have no outcome to predict
        r["confirmed"] = int(status[r["id"]] == "CNF")
        out.append(r)
    return out


def simulate(seed: int = 7, trains: int = 8, start: dt.date = dt.date(2025, 1, 1), days: int = 365) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    ts = make_trains(rng, trains)
    rows = []
    for d in range(days):
        run_date = start + dt.timedelta(days=d)
        for tr in ts:
            for cls, cap in CLASSES.items():
                rows.extend(simulate_pool(rng, tr, run_date, cls, cap))
    df = pd.DataFrame(rows).drop(columns=["id"])
    df["run_date"] = pd.to_datetime(df["run_date"])
    df["booked_at"] = df["run_date"] - pd.to_timedelta(df["days_before"], unit="D")
    return df


if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else "data/wl_history.csv"
    df = simulate()
    import pathlib
    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(out, index=False)
    print(f"{len(df)} waitlisted bookings, confirmation rate {df.confirmed.mean():.3f} -> {out}")
