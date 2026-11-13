# Model card: waitlist confirmation (`wl-confirm-2026.11-sim1`)

## Status: NOT FOR REAL PASSENGERS

This model was trained on **synthetic** bookings from `analytics/railops_ml/simulate.py`. No real reservation history existed in this project. The feature is **off by default** (`railops.prediction.wl.enabled=false`) and is on only in the `local` profile.

Before enabling it in any real environment:
1. Retrain on real chart outcomes from at least 12 months, so every festival season appears in training at least once.
2. Re-run the evaluation with the same protocol, and replace this card.
3. Check that Brier skill is above 0 and the calibration gap is within ±0.05 on the newest two months.

## What it predicts

The probability that a waitlisted **GN-quota, 3A or SL** party holds berths (CNF or RAC) once the chart is prepared.

- The output is advisory. It is shown on the booking page and offered for the next WL number of a pool.
- It is never read by allocation, promotion, charting, refunds or quota logic. `PredictionIsolationTest` enforces this.
- Other classes and quotas get `reason: NOT_COVERED` rather than an extrapolated number.

**Label definition.** A party is **confirmed** if every passenger who was waitlisted ended `PROMOTED_CNF` or `PROMOTED_RAC`.
- RAC counts as confirmed because the passenger boards.
- Bookings that are cancelled before the chart have no label. This applies in training and in monitoring (`VOID`).

## Inputs (all observable at prediction time)

Features are read from the reservation database at the moment of prediction. `features.py` and `WaitlistModel.java` must stay in sync, and the golden parity test checks this to 1e-9.

| Feature | Source | Clip |
|---|---|---|
| WL ratio = current WL position ÷ pool capacity, plus hinges at 0.1, 0.25 and 0.5 | `waitlist_entry` (PENDING + WAITING at or ahead of the party's last `wl_seq`); capacity is the max `cnf_capacity` across the pool's segments | 0 to 1 |
| log(1 + days to departure) | now vs 00:00 IST on the run date | 0 to 60 days |
| Party size still waiting | `waitlist_entry` | 1 to 6 |
| Bookings in the pool in the last 7 days ÷ capacity | `booking.created_at` | 0 to 3 |
| User cancellations so far ÷ capacity | `cancellation.kind = 'USER'` | 0 to 2 |
| Fri/Sun departure; Tue/Wed departure | run date | |
| Festival window; summer month | public calendar in the model JSON (**not** the simulator's latent demand curve) | |
| Class SL | | |
| Per-train prior (smoothed confirmation rate) | **training window only**; unknown trains get the global rate | |

No personal data is used or logged.

## Evaluation

The full report is in [evaluation.md](evaluation.md). In summary:
- **Split.** By run date: train Jan–Aug, validation Sep–Oct, test Nov–Dec.
- **Test results.** AUC 0.963, Brier 0.063, ECE 0.011.
- **Baseline.** The counter-clerk heuristic scores AUC 0.917, Brier 0.097, ECE 0.090.
- **Leakage.** A random split would have looked about 6% better on log-loss.

## Known limitations

- **Synthetic training data.** The simulator's mechanics are assumptions. They include:
  - the cancellation hazard
  - unused Tatkal berths released at chart
  - festival demand multipliers
  - one-segment pools

  Real data will differ in ways these numbers cannot show.
- **Booking-time snapshots vs serving at any time.** Training rows are snapshots taken when a booking was made. Serving re-scores the same features later, for example after cancellations have moved the party up.
  - The features mean the same thing at both times, but the distribution of "days before" at serving time differs.
  - Monitoring scores only the **first** prediction for each booking, because that one is closest to the training distribution.
- **Partial-route journeys.** Capacity is approximated by the widest segment. Real WL clearance on a partial route depends on segment-level vacancies that this model does not see.
- **Per-train priors go stale.** New trains get the global prior until the model is retrained.
- **Seasonality coverage.** One year of training data means each festival has been seen once.

## Monitoring and rollback

| Signal | Where |
|---|---|
| `prediction_wl_brier`, `prediction_wl_brier_skill`, `prediction_wl_calibration_gap`, `prediction_wl_resolved_count` (30-day window, first prediction per booking) | `PredictionMonitor`, every 10 minutes after charts |
| `prediction_wl_served_total{source}`, `prediction_wl_errors_total`, `prediction_wl_log_failures_total` | `WaitlistPredictionService` |
| Alerts: `RailopsWlModelWorseThanBaseRate` (skill < 0, n ≥ 500, 6 h), `RailopsWlModelMiscalibrated` (over-confident by > 0.08), `RailopsWlPredictionErrors` | `deploy/observability/alerts.yaml` |
| Per-run Brier, next to occupancy and the WL funnel | `GET /api/v1/staff/runs/{runId}/analytics` |
| Model version and 30-day calibration | `GET /api/v1/staff/ml/wl-model` (ADMIN) |

Over-confidence is the harmful direction: a passenger keeps a WL ticket that will not confirm and misses the chance to make other plans. That is why the calibration-gap alert is one-sided.

**Rollback.** Set `RAILOPS_PREDICTION_WL_ENABLED=false` and restart. The prediction endpoints then return 404, and the web app shows nothing because it treats any error as "no estimate". No booking data depends on predictions, so rollback has no data impact.

**Retrain.** Run `scripts/ml-train.sh`. It retrains, runs the Python pipeline tests, installs the model JSON and golden cases, and runs the Java parity test.

Three gates block a bad export:
- the monotonicity assertion (`train.py`)
- fail-fast model loading in Java, which rejects a model on feature order, featureVersion, array length, a non-monotone calibration map or missing clip bounds
- the parity test

## Privacy and fairness

- **Logged data.** `wl_prediction` stores the booking id, pool-level feature values and the probability. It holds no names, contacts or ages.
- **Access.** The app role can insert and update but not delete. Retention is an owner job; 400 days is suggested, enough for a year-over-year retrain.
- **Fairness.** Inputs describe the pool and the timing, never the passenger. Two passengers at the same queue position on the same train get the same number.
- **Quota fairness gap.** The model doesn't cover quotas such as Ladies or Senior Citizen. Extending to them needs per-quota evaluation, because their clearance rules differ.
