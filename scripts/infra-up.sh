#!/usr/bin/env bash
# Local infrastructure for development and drills: PostgreSQL, Redis, single-node Kafka (embedded KRaft).
# Idempotent: starts only what isn't running. Kafka's bootstrap address is written to /tmp/railops-kafka.bootstrap.
set -uo pipefail
cd "$(dirname "$0")/.."
up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
up 5432 || { sudo service postgresql start >/dev/null; sleep 2; }
up 6379 || redis-server --daemonize yes --save '' --appendonly no >/dev/null
if ! pgrep -f in.railops.tools.LocalKafka >/dev/null; then
  CP=tools/local-kafka/target/classes:$(cat /tmp/kcp.txt 2>/dev/null)
  [ -s /tmp/kcp.txt ] || { (cd tools/local-kafka && mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/kcp.txt) ; CP=tools/local-kafka/target/classes:$(cat /tmp/kcp.txt); }
  rm -f /tmp/railops-kafka.bootstrap
  nohup java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$CP" in.railops.tools.LocalKafka > /tmp/kafka.log 2>&1 &
  for _ in $(seq 1 60); do [ -s /tmp/railops-kafka.bootstrap ] && break; sleep 1; done
fi
echo "postgres:$(up 5432 && echo up || echo DOWN) redis:$(up 6379 && echo up || echo DOWN) kafka:$(cat /tmp/railops-kafka.bootstrap 2>/dev/null || echo DOWN)"
