#!/usr/bin/env bash
# Retrain the WL model, run the pipeline tests, and install model + golden parity cases into reservation-service.
# The Java parity test (WaitlistModelTest) then proves serving matches training. Never edit wl-model.json by hand.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=analytics/out
python3 -I -c "import sys; sys.path.insert(0,'analytics'); from railops_ml.train import main; main('$OUT')"
cp "$OUT/wl-model.json" reservation-service/src/main/resources/ml/wl-model.json
cp "$OUT/golden_cases.json" reservation-service/src/test/resources/ml/golden_cases.json
python3 -m pytest analytics/tests -q
mvn -q -o -pl reservation-service -am -Dtest='WaitlistModelTest' -Dsurefire.failIfNoSpecifiedTests=false test
echo "installed $(python3 -c "import json;print(json.load(open('$OUT/wl-model.json'))['modelVersion'])"); evaluation: $OUT/evaluation.json"
