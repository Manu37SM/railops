#!/usr/bin/env bash
# One entry point for every test tier. Usage:
#   scripts/test-all.sh            unit + contract + integration + web       (no running stack needed)
#   scripts/test-all.sh --e2e      ... then the end-to-end suites, each on a freshly reset stack
#   scripts/test-all.sh --only e2e
#
# Tiers (see docs/testing.md):
#   unit         *Test, no infrastructure: domain policies, crypto, ABAC, JWT mapping, gates, scoring
#   contract     *ContractTest (run with unit): event schemas + HTTP fixtures in contracts/, both sides
#   integration  *IT on real PostgreSQL (+ embedded Kafka for the relay): RAILOPS_IT_PG or Docker/Testcontainers
#   web          Angular unit tests (Vitest)
#   ml           Python pipeline tests (analytics/tests): split, leakage, clipping, monotonicity gate, golden inputs
#   e2e          Python suites against the running services: security, lifecycle, saga, edge, ui
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
export PGPASSWORD=${PGPASSWORD:-railops}
export RAILOPS_IT_PG=${RAILOPS_IT_PG:-"jdbc:postgresql://localhost:5432/postgres?user=railops&password=railops"}

ONLY=""; E2E=0
while [ $# -gt 0 ]; do
  case "$1" in
    --e2e) E2E=1 ;;
    --only) ONLY="$2"; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done
want() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

declare -a SUMMARY
FAILED=0
record() {  # name, exit code, detail
  if [ "$2" -eq 0 ]; then SUMMARY+=("PASS  $1  $3"); else SUMMARY+=("FAIL  $1  $3"); FAILED=1; fi
}
bad() {     # sums failures + errors over report files
  cat "$@" 2>/dev/null | grep -h "Tests run:" | awk -F'[ ,]+' '{for(i=1;i<=NF;i++) if($i=="Failures:"||$i=="Errors:") s+=$(i+1)} END {print s+0}'
}
count() {   # sums "Tests run: N" over report files
  cat "$@" 2>/dev/null | grep -h "Tests run:" | awk -F'[ ,]+' '{for(i=1;i<=NF;i++) if($i=="run:") s+=$(i+1)} END {print s+0}'
}

if want unit || want integration; then
  GOAL=test; want integration && GOAL=verify; [ -z "$ONLY" ] && GOAL=verify
  echo "== maven $GOAL (unit + contract$( [ "$GOAL" = verify ] && echo ' + integration'))"
  mvn -q -B install -DskipTests -pl railops-common,railops-test-support -am >/dev/null 2>&1
  # Phase 15 fix: this used to be `mvn | tee | grep || true; rc=${PIPESTATUS[0]}`. After `|| true` runs,
  # PIPESTATUS describes `true`, so rc was always 0 and a failing build was reported as PASS.
  mvn -B $GOAL > /tmp/railops-mvn.log 2>&1
  rc=$?
  grep -E "Tests run:.*(Fail|Err)[a-z]*: [1-9]|BUILD|ERROR\] +[A-Za-z]+(Test|IT)" /tmp/railops-mvn.log || true
  U=$(count */target/surefire-reports/*.txt tools/*/target/surefire-reports/*.txt)
  I=$(count */target/failsafe-reports/*.txt)
  UF=$(bad */target/surefire-reports/*.txt tools/*/target/surefire-reports/*.txt)
  IF=$(bad */target/failsafe-reports/*.txt)
  # Each tier fails on its own failure counts (belt and braces if the exit code is ever lost again). A non-zero
  # build exit with NO test failures (compilation, plugin, context error before tests) fails both tiers.
  broke=$( [ "$rc" -ne 0 ] && [ "$UF" -eq 0 ] && [ "$IF" -eq 0 ] && echo 1 || echo 0 )
  record "java unit+contract" "$( [ "$UF" -gt 0 ] || [ "$broke" = 1 ] && echo 1 || echo 0)" "$U tests, $UF failed"
  [ "$GOAL" = verify ] && record "java integration" "$( [ "$IF" -gt 0 ] || [ "$broke" = 1 ] && echo 1 || echo 0)" "$I tests, $IF failed"
fi

if want ml; then
  echo "== ml pipeline (pytest)"
  python3 -m pytest analytics/tests -q > /tmp/railops-ml-test.log 2>&1
  rc=$?
  record "ml pipeline" "$rc" "$(grep -Eo '[0-9]+ passed' /tmp/railops-ml-test.log | tail -1)"
fi

if want web; then
  echo "== angular (vitest)"
  (cd railops-web && npx ng test --watch=false > /tmp/railops-web-test.log 2>&1)
  rc=$?
  record "angular unit" "$rc" "$(sed 's/\x1b\[[0-9;]*m//g' /tmp/railops-web-test.log | grep -Eo 'Tests +[0-9]+ passed' | tail -1)"
fi

if [ "$E2E" -eq 1 ] || [ "$ONLY" = e2e ]; then
  fresh() {
    pkill -f 'reservation-service-0.1.0|identity-service-0.1.0|payment-service-0.1.0|notification-service-0.1.0|api-gateway-0.1.0' || true
    sleep 3; redis-cli flushall >/dev/null
    bash scripts/start-local.sh --fresh >/dev/null
    for p in 8080 8081 8082 8083 8084; do
      for _ in $(seq 1 60); do curl -sf -o /dev/null "localhost:$p/actuator/health" && break; [ $p = 8080 ] && break; sleep 1; done
    done
    sleep 5
  }
  mvn -q -B install -DskipTests >/dev/null 2>&1
  for s in security_e2e lifecycle_e2e saga_e2e edge_e2e; do
    echo "== e2e $s"
    fresh
    out=$(timeout 600 python3 -I scripts/$s.py 2>&1); rc=$?
    echo "$out" | grep -E "^FAIL" || true
    record "e2e $s" "$rc" "$(echo "$out" | grep -Eo '[0-9]+/[0-9]+ (checks )?passed' | tail -1)"
  done
  if curl -sf -o /dev/null localhost:4200; then
    fresh
    out=$(timeout 600 python3 -I scripts/ui_e2e.py 2>&1); rc=$?
    record "e2e ui_e2e" "$rc" "$(echo "$out" | grep -Eo '[0-9]+/[0-9]+ (checks )?passed' | tail -1)"
  else
    SUMMARY+=("SKIP  e2e ui_e2e  (start: cd railops-web && npx ng serve)")
  fi
  psql -h localhost -U railops -d railops -q -f scripts/invariants.sql > /tmp/railops-invariants.txt 2>&1
  bad=$(awk '/double_booked/{getline; getline; print $1}' /tmp/railops-invariants.txt)
  record "post-e2e inventory invariants" "$([ "${bad:-1}" = "0" ] && echo 0 || echo 1)" "double_booked=${bad:-?}"
fi

echo
echo "================ summary ================"
printf '%s\n' "${SUMMARY[@]}"
exit $FAILED
