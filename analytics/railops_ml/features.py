"""Point-in-time features. Every feature must be computable from what the reservation DB knows AT prediction time.

Contract shared with the Java scorer (WaitlistFeatures.java). Changing a feature here without changing it there is
caught by the golden parity test (tests/golden_cases.json is scored by both sides).
"""
from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd

FEATURE_VERSION = 1

# Public festival calendar (what operations staff can publish). Deliberately NOT the simulator's latent curve.
FESTIVAL_WINDOWS = [  # (month_from, day_from, month_to, day_to), inclusive, same-year windows
    (3, 10, 3, 20),
    (10, 15, 11, 10),
    (12, 20, 12, 31),
    (1, 1, 1, 3),
]
SUMMER_MONTHS = (5, 6)
CLASSES = ("3A", "SL")


def festival_flag(d: dt.date) -> int:
    md = (d.month, d.day)
    return int(any((m1, d1) <= md <= (m2, d2) for m1, d1, m2, d2 in FESTIVAL_WINDOWS))


CLIP = {"wl_ratio": (0.0, 1.0), "days_before": (0.0, 60.0), "party": (1.0, 6.0),
        "velocity": (0.0, 3.0), "cancel_ratio": (0.0, 2.0)}

WL_KNOTS = (0.1, 0.25, 0.5)
FEATURES = ["wl_ratio", *[f"wl_hinge_{k}" for k in WL_KNOTS], "log_days", "party", "velocity", "cancel_ratio",
            "weekend_peak", "midweek", "festival", "summer", "cls_SL", "train_prior"]


def build(df: pd.DataFrame, train_prior: dict[str, float], global_prior: float) -> pd.DataFrame:
    run = pd.to_datetime(df["run_date"])
    X = pd.DataFrame(index=df.index)
    # Clipping bounds are part of the contract (CLIP below); they keep the quadratic WL term monotone and stop
    # out-of-range inputs (WL beyond cap after a capacity change, party > 6) extrapolating.
    X["wl_ratio"] = (df["wl_position"] / df["capacity"]).clip(*CLIP["wl_ratio"])
    for k in WL_KNOTS:                                       # piecewise-linear in WL ratio: no vertex to bend back up
        X[f"wl_hinge_{k}"] = (X["wl_ratio"] - k).clip(lower=0.0)
    X["log_days"] = np.log1p(df["days_before"].clip(*CLIP["days_before"]))
    X["party"] = df["party"].astype(float).clip(*CLIP["party"])
    X["velocity"] = (df["bookings_last7"] / df["capacity"]).clip(*CLIP["velocity"])
    X["cancel_ratio"] = (df["cancels_so_far"] / df["capacity"]).clip(*CLIP["cancel_ratio"])
    wd = run.dt.weekday
    X["weekend_peak"] = wd.isin([4, 6]).astype(float)       # Fri, Sun departures
    X["midweek"] = wd.isin([1, 2]).astype(float)
    X["festival"] = [float(festival_flag(d.date())) for d in run]
    X["summer"] = run.dt.month.isin(SUMMER_MONTHS).astype(float)
    X["cls_SL"] = (df["cls"] == "SL").astype(float)
    X["train_prior"] = [train_prior.get(str(t), global_prior) for t in df["train"]]
    return X[FEATURES]


def train_priors(df: pd.DataFrame, k: float = 200.0) -> tuple[dict[str, float], float]:
    """Smoothed per-train confirmation rate, computed ONLY on the rows passed in (the training window)."""
    g = float(df["confirmed"].mean())
    s = df.groupby(df["train"].astype(str))["confirmed"].agg(["sum", "count"])
    return {t: float((r["sum"] + k * g) / (r["count"] + k)) for t, r in s.iterrows()}, g
