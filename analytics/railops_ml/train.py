"""Train, evaluate and export the waitlist-confirmation model.

Evaluation protocol (the point of this file):
  * Split by RUN DATE, not randomly: train Jan-Aug, validation Sep-Oct (calibration fitted here), test Nov-Dec.
    The test window contains a festival peak the model has seen only once (Holi) -> honest stress.
  * Per-train priors are computed from the train window only.
  * A random split is reported next to it to show how much leakage flatters the numbers.
  * Calibration (Brier, ECE, reliability table) matters more than AUC: the passenger reads the number as a probability.

Export: an interpretable logistic model + isotonic map as JSON, scored identically in Java (see export()).
HistGradientBoosting is evaluated as a challenger; it is only exported if it beats the logistic model on test log-loss
by more than MIN_GAIN, because a tree ensemble in JSON is much harder to keep in parity and to explain.
"""
from __future__ import annotations

import json
import pathlib
import sys

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score
from sklearn.model_selection import train_test_split

from . import features as F
from .simulate import simulate

MIN_GAIN = 0.01
MODEL_VERSION = "wl-confirm-2026.11-sim1"


def ece(y, p, bins=10) -> float:
    edges = np.linspace(0, 1, bins + 1)
    idx = np.clip(np.digitize(p, edges) - 1, 0, bins - 1)
    tot = 0.0
    for b in range(bins):
        m = idx == b
        if m.any():
            tot += m.mean() * abs(y[m].mean() - p[m].mean())
    return float(tot)


def reliability(y, p, bins=10) -> list[dict]:
    edges = np.linspace(0, 1, bins + 1)
    idx = np.clip(np.digitize(p, edges) - 1, 0, bins - 1)
    return [dict(bin=f"{edges[b]:.1f}-{edges[b+1]:.1f}", n=int((idx == b).sum()),
                 predicted=round(float(p[idx == b].mean()), 4), observed=round(float(y[idx == b].mean()), 4))
            for b in range(bins) if (idx == b).any()]


def metrics(y, p) -> dict:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return dict(n=int(len(y)), base_rate=round(float(y.mean()), 4), auc=round(float(roc_auc_score(y, p)), 4),
                logloss=round(float(log_loss(y, p)), 4), brier=round(float(brier_score_loss(y, p)), 4),
                ece=round(ece(y, p), 4))


def heuristic(df) -> np.ndarray:
    """What a counter clerk says: 'WL up to ~20% of capacity usually clears'. Baseline to beat."""
    r = df["wl_position"] / df["capacity"]
    return np.clip(0.75 - 2.2 * r, 0.02, 0.95)


class Logistic:
    def __init__(self):
        self.mean = self.std = None
        self.lr = LogisticRegression(C=1.0, max_iter=2000)
        self.iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)

    def _z(self, X):
        return (X.values - self.mean) / self.std

    def fit(self, X, y):
        self.mean = X.values.mean(axis=0)
        self.std = X.values.std(axis=0)
        self.std[self.std == 0] = 1.0
        self.lr.fit(self._z(X), y)
        return self

    def raw(self, X):
        return self.lr.predict_proba(self._z(X))[:, 1]

    def calibrate(self, X, y, method="isotonic"):
        if method == "identity":                    # exported as the 2-point map (0,0)-(1,1): same Java code path
            self.iso.fit([0.0, 1.0], [0.0, 1.0])
        else:
            self.iso.fit(self.raw(X), y)
        self.calibration = method
        return self

    def predict(self, X):
        return self.iso.predict(self.raw(X))


def choose_calibration(df) -> tuple[str, dict]:
    """Backtest one step earlier (fit Jan-Jun, calibrate Jul-Aug, score Sep-Oct) and keep isotonic only if it beats
    the raw logistic there. Fitting AND judging calibration on Sep-Oct would be circular."""
    m = df["run_date"].dt.month
    a, b, c = df[m <= 6], df[m.isin([7, 8])], df[m.isin([9, 10])]
    pr, g = F.train_priors(a)
    base = Logistic().fit(F.build(a, pr, g), a["confirmed"].values)
    Xc, yc = F.build(c, pr, g), c["confirmed"].values
    raw = metrics(yc, base.raw(Xc))
    iso = metrics(yc, base.calibrate(F.build(b, pr, g), b["confirmed"].values).predict(Xc))
    return ("isotonic" if iso["logloss"] < raw["logloss"] else "identity"), {"raw": raw, "isotonic": iso}


def assert_monotone(model: "Logistic"):
    """P(confirm) must not rise as WL position worsens, all else equal. With a hinge basis the logit is piecewise
    linear in wl_ratio, so it suffices that the slope on every segment is negative. A failure blocks export."""
    slope = model.lr.coef_[0][F.FEATURES.index("wl_ratio")] / model.std[F.FEATURES.index("wl_ratio")]
    seg = {"0": round(slope, 4)}
    for k in F.WL_KNOTS:
        i = F.FEATURES.index(f"wl_hinge_{k}")
        slope += model.lr.coef_[0][i] / model.std[i]
        seg[str(k)] = round(slope, 4)
    if max(seg.values()) >= 0:
        raise AssertionError(f"logit not decreasing in wl_ratio; segment slopes {seg}")
    return {"segment_slopes_from": seg}


def split(df):
    m = df["run_date"].dt.month
    return df[m <= 8], df[m.isin([9, 10])], df[m >= 11]


def slices(df, y, p) -> dict:
    out = {}
    r = (df["wl_position"] / df["capacity"]).values
    for name, mask in {
        "wl_ratio<0.15": r < 0.15, "wl_ratio 0.15-0.4": (r >= 0.15) & (r < 0.4), "wl_ratio>=0.4": r >= 0.4,
        "festival": np.array([F.festival_flag(d.date()) == 1 for d in df["run_date"]]),
        "days_before<7": (df["days_before"] < 7).values, "days_before>=45": (df["days_before"] >= 45).values,
        "class 3A": (df["cls"] == "3A").values, "class SL": (df["cls"] == "SL").values,
    }.items():
        if mask.sum() > 50 and 0 < y[mask].mean() < 1:
            out[name] = metrics(y[mask], p[mask])
        elif mask.sum() > 0:
            out[name] = dict(n=int(mask.sum()), base_rate=round(float(y[mask].mean()), 4),
                             mean_pred=round(float(p[mask].mean()), 4))
    return out


def export(model: Logistic, priors, gprior, path: pathlib.Path):
    iso_x = model.iso.X_thresholds_.tolist()
    iso_y = model.iso.y_thresholds_.tolist()
    doc = {
        "modelVersion": MODEL_VERSION,
        "featureVersion": F.FEATURE_VERSION,
        "kind": "logistic+isotonic",
        "calibration": model.calibration,
        "clip": {k: list(v) for k, v in F.CLIP.items()},
        "trainedOn": "SYNTHETIC simulation (railops_ml.simulate, seed 7). Not valid for real passengers.",
        "features": F.FEATURES,
        "mean": model.mean.tolist(),
        "std": model.std.tolist(),
        "coef": model.lr.coef_[0].tolist(),
        "intercept": float(model.lr.intercept_[0]),
        "isotonic": {"x": iso_x, "y": iso_y},
        "trainPrior": {"global": gprior, "byTrain": priors},
        "festivalWindows": [list(w) for w in F.FESTIVAL_WINDOWS],
        "summerMonths": list(F.SUMMER_MONTHS),
        "bands": [{"name": "LOW", "below": 0.35}, {"name": "MEDIUM", "below": 0.7}, {"name": "HIGH", "below": 1.01}],
    }
    path.write_text(json.dumps(doc, indent=1))
    return doc


def golden(model: Logistic, priors, gprior, df: pd.DataFrame, path: pathlib.Path, n=40, seed=3):
    """Cases scored by both Python and Java. Includes edge cases the sample may not hit."""
    s = df.sample(n=n, random_state=seed).copy()
    edge = pd.DataFrame([
        dict(train="99999", run_date=pd.Timestamp("2025-10-20"), cls="SL", days_before=0.2, party=1, capacity=72,
             wl_position=1, bookings_last7=0, cancels_so_far=0),        # unknown train, festival, near chart
        dict(train=s.iloc[0]["train"], run_date=pd.Timestamp("2025-12-31"), cls="3A", days_before=59.9, party=4,
             capacity=64, wl_position=64, bookings_last7=90, cancels_so_far=0),   # WL at cap, ARP opening
        dict(train=s.iloc[1]["train"], run_date=pd.Timestamp("2025-03-10"), cls="3A", days_before=0.0, party=6,
             capacity=64, wl_position=200, bookings_last7=0, cancels_so_far=150),  # out-of-range inputs: clip path
    ])
    s = pd.concat([s, edge], ignore_index=True)
    X = F.build(s, priors, gprior)
    p = model.predict(X)
    cases = []
    for i, row in s.reset_index(drop=True).iterrows():
        cases.append(dict(train=str(row["train"]), runDate=row["run_date"].date().isoformat(), cls=row["cls"],
                          daysBefore=float(row["days_before"]), party=int(row["party"]), capacity=int(row["capacity"]),
                          wlPosition=int(row["wl_position"]), bookingsLast7=int(row["bookings_last7"]),
                          cancelsSoFar=int(row["cancels_so_far"]),
                          features=[float(v) for v in X.iloc[i].values], probability=float(p[i])))
    path.write_text(json.dumps({"modelVersion": MODEL_VERSION, "tolerance": 1e-9, "cases": cases}, indent=1))


def main(out_dir="out"):
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    df = simulate()
    tr, va, te = split(df)
    priors, g = F.train_priors(tr)
    Xtr, Xva, Xte = (F.build(d, priors, g) for d in (tr, va, te))
    ytr, yva, yte = (d["confirmed"].values for d in (tr, va, te))

    report = {"rows": {"train": len(tr), "validation": len(va), "test": len(te)}, "models": {}}
    report["models"]["heuristic"] = metrics(yte, heuristic(te))
    lr = Logistic().fit(Xtr, ytr)
    report["models"]["logistic_uncalibrated"] = metrics(yte, lr.raw(Xte))
    report["monotonicity"] = assert_monotone(lr)
    report["models"]["logistic_isotonic"] = metrics(yte, Logistic.predict(
        Logistic().fit(Xtr, ytr).calibrate(Xva, yva), Xte))
    method, report["calibration_backtest"] = choose_calibration(df)
    lr.calibrate(Xva, yva, method)
    report["calibration"] = method
    p_lr = lr.predict(Xte)
    report["models"]["exported"] = metrics(yte, p_lr)

    hgb = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.06, max_leaf_nodes=31,
                                         early_stopping=True, random_state=0).fit(Xtr, ytr)
    iso_h = IsotonicRegression(out_of_bounds="clip").fit(hgb.predict_proba(Xva)[:, 1], yva)
    p_hgb = iso_h.predict(hgb.predict_proba(Xte)[:, 1])
    report["models"]["hist_gbdt_isotonic"] = metrics(yte, p_hgb)

    # leakage demonstration: random split, priors computed on everything (the classic mistake)
    allp, ag = F.train_priors(df)
    rtr, rte = train_test_split(df, test_size=0.2, random_state=0)
    leak = Logistic().fit(F.build(rtr, allp, ag), rtr["confirmed"].values)
    report["leaky_random_split_logistic"] = metrics(rte["confirmed"].values, leak.raw(F.build(rte, allp, ag)))

    gain = report["models"]["exported"]["logloss"] - report["models"]["hist_gbdt_isotonic"]["logloss"]
    report["decision"] = (f"export logistic+{method} (GBDT log-loss gain {gain:.4f} <= {MIN_GAIN})" if gain <= MIN_GAIN
                          else f"GBDT gain {gain:.4f} > {MIN_GAIN}: consider exporting GBDT (not implemented: logistic exported)")
    report["reliability_test"] = reliability(yte, p_lr)
    report["slices_test"] = slices(te, yte, p_lr)
    report["coefficients_standardized"] = dict(zip(F.FEATURES, [round(c, 4) for c in lr.lr.coef_[0]]))

    export(lr, priors, g, out / "wl-model.json")
    golden(lr, priors, g, te, out / "golden_cases.json")
    (out / "evaluation.json").write_text(json.dumps(report, indent=1))
    plot(yte, p_lr, heuristic(te), out / "reliability.png")
    return report


def plot(y, p, h, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(5.2, 5), dpi=120)
    ax.plot([0, 1], [0, 1], color="#9aa0a6", lw=1, ls="--", label="perfect")
    for name, q, c in (("exported model", p, "#1f6feb"), ("heuristic", h, "#d9822b")):
        r = reliability(y, q)
        ax.plot([b["predicted"] for b in r], [b["observed"] for b in r], marker="o", color=c, lw=2, label=name)
    ax.set_xlabel("predicted probability")
    ax.set_ylabel("observed confirmation rate")
    ax.set_title("Reliability on held-out Nov-Dec (synthetic)")
    ax.spines[["top", "right"]].set_visible(False)
    ax.legend(frameon=False, loc="upper left")
    fig.tight_layout()
    fig.savefig(path)


if __name__ == "__main__":
    r = main(sys.argv[1] if len(sys.argv) > 1 else "out")
    print(json.dumps({k: r[k] for k in ("rows", "models", "leaky_random_split_logistic", "decision")}, indent=1))
