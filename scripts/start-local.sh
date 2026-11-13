#!/usr/bin/env bash
# Starts the full local stack against a running local Kafka (tools/local-kafka) and PostgreSQL.
# Usage: scripts/start-local.sh [--fresh]   (--fresh wipes reservation/identity/payment/notification state first)
set -euo pipefail
cd "$(dirname "$0")/.."
BOOT=$(cat "${TMPDIR:-/tmp}/railops-kafka.bootstrap")
export KAFKA_BOOTSTRAP="$BOOT"
export PGPASSWORD=${PGPASSWORD:-railops}

if [[ "${1:-}" == "--fresh" ]]; then
  bash scripts/reset-local.sh >/dev/null
  sudo -u postgres psql -d railops -q -c 'DROP SCHEMA IF EXISTS payment CASCADE; DROP SCHEMA IF EXISTS notification CASCADE;' 2>/dev/null || true
  psql -h localhost -U railops -d railops -q -c "UPDATE reservation.train_run SET chart1_due_at = now() + interval '1 day', chart2_due_at = now() + interval '1 day 3 hours';"
fi

run() { nohup java -Duser.timezone=UTC -jar "$1/target/$1-0.1.0-SNAPSHOT.jar" --spring.profiles.active=local "${@:2}" > "/tmp/$1.log" 2>&1 & }
run identity-service
sleep 12   # others fetch JWKS / tokens from identity
run reservation-service --server.port=8081
run payment-service
run notification-service
run api-gateway          # public edge on :8080 (actuator on :8090)
echo "starting; Kafka at $BOOT; logs in /tmp/<service>.log"
