#!/usr/bin/env bash
# Benchmarks availability in three modes on the SAME build. Reports throughput, latency and server CPU per request
# (the honest metric on a small shared box, where client, JVM, Postgres, Redis and Kafka compete for the same cores).
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=reservation-service/target/reservation-service-0.1.0-SNAPSHOT.jar
URL_FIXED="http://localhost:8081/api/v1/runs/1/availability?from=0&to=4"
export KAFKA_BOOTSTRAP=$(cat /tmp/railops-kafka.bootstrap)

cpu_ticks() { awk '{print $14+$15}' "/proc/$(pgrep -f reservation-service-0.1.0)/stat"; }

run_mode() {  # $1 label, rest: extra args
  local label=$1; shift
  pkill -f reservation-service-0.1.0 || true; sleep 2; redis-cli flushall >/dev/null
  nohup java -Duser.timezone=UTC -jar "$JAR" --spring.profiles.active=local --server.port=8081 "$@" > /tmp/reservation-service.log 2>&1 &
  for _ in $(seq 60); do curl -sf localhost:8081/actuator/health/readiness >/dev/null 2>&1 && break; sleep 1; done
  local c0 out c1 n
  c0=$(cpu_ticks); out=$(python3 -I scripts/loadgen.py "$URL_FIXED" 64 15); c1=$(cpu_ticks)
  n=$(echo "$out" | grep -oE "200: [0-9]+" | grep -oE "[0-9]+$")
  printf "%-34s %s | server CPU %.2f ms/req\n" "$label" "$out" "$(python3 -c "print(($c1-$c0)*10/$n)")"
}

run_mode "PostgreSQL only"            --railops.availability.source=db    --railops.availability.micro-cache-ms=0
run_mode "Redis read model"           --railops.availability.source=redis --railops.availability.micro-cache-ms=0
run_mode "Redis + 500 ms micro-cache" --railops.availability.source=redis --railops.availability.micro-cache-ms=500
