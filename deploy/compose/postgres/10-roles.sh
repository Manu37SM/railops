#!/usr/bin/env bash
# Runs once, on an empty data directory (docker-entrypoint-initdb.d). Per service: an OWNER role that Flyway uses
# (owns the schema, runs DDL) and an APP role the service connects with (granted DML by the service's own
# V*__runtime_grants.sql). No role can touch another service's schema. Passwords come from mounted secrets.
set -euo pipefail
SECRETS_DIR="${SECRETS_DIR:-/run/secrets}"
# Validate EVERY secret up front: a read inside the $(...) below can fail without stopping the script, and the
# result would be CREATE ROLE ... PASSWORD '' (a role with no password). Found in Phase 12 by compose_sim.py.
for f in ops_readonly_db_password ops_breakglass_db_password; do
  [ -r "$SECRETS_DIR/$f" ] || { echo "FATAL: cannot read $SECRETS_DIR/$f" >&2; exit 1; }
done
for svc in reservation identity payment notification; do
  for kind in owner app; do
    f="$SECRETS_DIR/${svc}_${kind}_db_password"
    [ -r "$f" ] || { echo "FATAL: cannot read $f as $(id -un)" >&2; exit 1; }
    [ "$(tr -d '\n' < "$f" | wc -c)" -ge 16 ] || { echo "FATAL: $f is empty or too short" >&2; exit 1; }
    tr -d '\n' < "$f" | grep -q "'" && { echo "FATAL: $f contains a quote" >&2; exit 1; }
  done
done
pw() { tr -d '\n' < "$SECRETS_DIR/$1"; }
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE ${POSTGRES_DB} FROM PUBLIC;
$(for svc in reservation identity payment notification; do cat <<EOS
CREATE ROLE ${svc}_owner LOGIN PASSWORD '$(pw ${svc}_owner_db_password)';
CREATE ROLE ${svc}_app   LOGIN PASSWORD '$(pw ${svc}_app_db_password)' CONNECTION LIMIT 60;
GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${svc}_owner, ${svc}_app;
-- V1 migrations say CREATE SCHEMA IF NOT EXISTS, and PostgreSQL checks the database-level CREATE privilege
-- before IF NOT EXISTS. Owners only (they already own their schema outright); app roles get no DDL anywhere.
GRANT CREATE ON DATABASE ${POSTGRES_DB} TO ${svc}_owner;
-- CREATE SCHEMA ... AUTHORIZATION needs SET ROLE on the new owner. A superuser (compose) always has it; on
-- PostgreSQL 16+ a CREATEROLE non-superuser (Neon's database owner) does not, until it grants itself membership.
DO \$\$ BEGIN
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    EXECUTE format('GRANT %I TO %I', '${svc}_owner', current_user);
  END IF;
END \$\$;
CREATE SCHEMA ${svc} AUTHORIZATION ${svc}_owner;
-- Flyway's history table lives in the schema it manages; the app role only ever reads it (validate on start).
ALTER ROLE ${svc}_owner SET search_path = ${svc}, public;
ALTER ROLE ${svc}_app   SET search_path = ${svc}, public;
-- The app never idles inside a transaction; if it ever leaks one, it can't sit on row locks forever (INC-002).
ALTER ROLE ${svc}_app   SET idle_in_transaction_session_timeout = '30s';
EOS
done)
-- Humans (INC-002: an operator's open psql transaction blocked a hot pool's bookings). Day to day: read-only.
-- Writes go through a break-glass role whose sessions cannot hold locks for long or wait long for them.
CREATE ROLE ops_readonly LOGIN PASSWORD '$(pw ops_readonly_db_password)' CONNECTION LIMIT 10;
ALTER ROLE ops_readonly SET default_transaction_read_only = on;
ALTER ROLE ops_readonly SET statement_timeout = '30s';
ALTER ROLE ops_readonly SET idle_in_transaction_session_timeout = '60s';
-- pg_read_all_stats: without it pg_stat_activity shows OTHER users' sessions with state/query/xact_start NULL and
-- the lock-holder runbook query silently finds nothing (INC-002 follow-up). Also needed by the metrics exporter.
-- Managed Postgres (Neon) runs this as a non-superuser owner that may not be allowed to grant built-in roles:
-- there it becomes a NOTICE instead of aborting the whole setup (ops_readonly then reads only what it's granted).
DO \$\$
BEGIN
  GRANT pg_read_all_data TO ops_readonly;
  GRANT pg_read_all_stats TO ops_readonly;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'not allowed to grant pg_read_all_data/pg_read_all_stats here; ops_readonly gets no extra read access';
END \$\$;
CREATE ROLE ops_breakglass NOLOGIN PASSWORD '$(pw ops_breakglass_db_password)' CONNECTION LIMIT 2;   -- ALTER ROLE ... LOGIN for an incident, audited
ALTER ROLE ops_breakglass SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE ops_breakglass SET lock_timeout = '2s';
ALTER ROLE ops_breakglass SET statement_timeout = '60s';
$(for svc in reservation identity payment notification; do echo "GRANT ${svc}_app TO ops_breakglass;"; done)
-- btree_gist backs reservation's no-double-berth EXCLUDE constraint. Both are trusted extensions: the superuser
-- (compose) or the database owner (Neon) can create them, so the service owner roles need no extra privilege.
CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA public;
CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
SQL
