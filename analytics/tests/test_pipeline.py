"""Pipeline guarantees that must survive retraining. Run: python3 -m pytest analytics/tests -q"""
import json
import pathlib
import sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from railops_ml import features as F  # noqa: E402
from railops_ml import train as T  # noqa: E402
from railops_ml.simulate import simulate  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
BUNDLED = ROOT / "reservation-service/src/main/resources/ml/wl-model.json"
GOLDEN = ROOT / "reservation-service/src/test/resources/ml/golden_cases.json"


@pytest.fixture(scope="module")
def small():
    return simulate(seed=11, trains=3, days=365)


def test_simulation_is_deterministic_per_seed():
    a = simulate(seed=5, trains=2, days=30)
    b = simulate(seed=5, trains=2, days=30)
    pd.testing.assert_frame_equal(a, b)


def test_labels_exist_only_for_bookings_that_kept_their_ticket(small):
    assert set(small["confirmed"].unique()) <= {0, 1}
    assert (small["wl_position"] >= 1).all()
    assert (small["days_before"].between(0, 60)).all()


def test_split_is_by_run_date_and_disjoint(small):
    tr, va, te = T.split(small)
    assert tr["run_date"].max() < va["run_date"].min() <= va["run_date"].max() < te["run_date"].min()
    assert len(tr) + len(va) + len(te) == len(small)


def test_train_priors_never_see_future_rows(small):
    tr, _, te = T.split(small)
    p_train, g_train = F.train_priors(tr)
    # Corrupting the TEST labels must not move the priors used to score the test set.
    te2 = te.assign(confirmed=1 - te["confirmed"])
    p_again, g_again = F.train_priors(tr)
    assert p_train == p_again and g_train == g_again
    assert F.build(te, p_train, g_train).equals(F.build(te2, p_train, g_train))


def test_features_are_point_in_time_and_contain_no_label(small):
    X = F.build(small, *F.train_priors(small))
    assert list(X.columns) == F.FEATURES
    assert "confirmed" not in X.columns and not X.isna().any().any()


def test_clipping_bounds_hold(small):
    extreme = small.head(5).assign(wl_position=10_000, party=12, bookings_last7=10_000, cancels_so_far=10_000,
                                   days_before=500.0)
    X = F.build(extreme, {}, 0.2)
    assert X["wl_ratio"].max() == F.CLIP["wl_ratio"][1]
    assert X["party"].max() == F.CLIP["party"][1]
    assert X["log_days"].max() == pytest.approx(np.log1p(F.CLIP["days_before"][1]))


def test_bundled_model_and_golden_file_come_from_the_same_run():
    model = json.loads(BUNDLED.read_text())
    golden = json.loads(GOLDEN.read_text())
    assert model["modelVersion"] == golden["modelVersion"]
    assert model["features"] == F.FEATURES and model["featureVersion"] == F.FEATURE_VERSION
    assert "SYNTHETIC" in model["trainedOn"], "a synthetic model must say so in its metadata"


def test_golden_features_recompute_from_inputs():
    golden = json.loads(GOLDEN.read_text())
    model = json.loads(BUNDLED.read_text())
    rows = pd.DataFrame([dict(train=c["train"], run_date=pd.Timestamp(c["runDate"]), cls=c["cls"],
                              days_before=c["daysBefore"], party=c["party"], capacity=c["capacity"],
                              wl_position=c["wlPosition"], bookings_last7=c["bookingsLast7"],
                              cancels_so_far=c["cancelsSoFar"]) for c in golden["cases"]])
    X = F.build(rows, model["trainPrior"]["byTrain"], model["trainPrior"]["global"])
    np.testing.assert_allclose(X.values, np.array([c["features"] for c in golden["cases"]]), atol=1e-12)


def test_monotonicity_gate_rejects_a_bending_model(small):
    tr, _, _ = T.split(small)
    m = T.Logistic().fit(F.build(tr, *F.train_priors(tr)), tr["confirmed"].values)
    T.assert_monotone(m)                                      # trained model passes
    m.lr.coef_[0][F.FEATURES.index("wl_hinge_0.5")] = 50.0    # force an upturn past 0.5
    with pytest.raises(AssertionError):
        T.assert_monotone(m)


def test_ece_and_reliability_basics():
    y = np.array([0, 0, 1, 1])
    assert T.ece(y, np.array([0.0, 0.0, 1.0, 1.0])) == 0
    assert T.ece(y, np.array([1.0, 1.0, 0.0, 0.0])) == pytest.approx(1.0)
    assert sum(b["n"] for b in T.reliability(y, np.array([0.05, 0.5, 0.55, 0.95]))) == 4
