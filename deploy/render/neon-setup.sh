#!/usr/bin/env bash
# One-time database setup on Neon: per-service schemas and roles (owner for migrations, app for runtime), the
# read-only ops role and the two extensions. Same SQL as the compose setup (deploy/compose/postgres/10-roles.sh),
# pointed at Neon. Safe to run once on an EMPTY database; it stops on the first error.
#
#   1. scripts/gen-secrets.sh                      (creates deploy/secrets/* once)
#   2. NEON_URL='postgresql://neondb_owner:...@ep-xxxx.REGION.aws.neon.tech/neondb?sslmode=require' \
#        deploy/render/neon-setup.sh
#
# Use the DIRECT connection string from Neon's "Connect" dialog (hostname WITHOUT "-pooler").
set -euo pipefail
cd "$(dirname "$0")/../.."

[ -n "${NEON_URL:-}" ] || { echo "Set NEON_URL to Neon's DIRECT connection string (see the header of this file)." >&2; exit 2; }
case "$NEON_URL" in
  *-pooler*) echo "NEON_URL is the POOLED string. Use the direct one (no '-pooler' in the host)." >&2; exit 2 ;;
esac
[ -d deploy/secrets ] || { echo "deploy/secrets is missing: run scripts/gen-secrets.sh first." >&2; exit 2; }
command -v psql >/dev/null || { echo "psql is required (PostgreSQL client)." >&2; exit 2; }

# Split the URL into the libpq variables 10-roles.sh expects (it calls psql --username U --dbname D).
read -r PGUSER PGPASSWORD PGHOST PGPORT PGDATABASE < <(python3 - "$NEON_URL" <<'PY'
import sys
from urllib.parse import urlparse, unquote
u = urlparse(sys.argv[1])
print(unquote(u.username or ""), unquote(u.password or ""), u.hostname or "", u.port or 5432, u.path.lstrip("/") or "neondb")
PY
)
export PGPASSWORD PGHOST PGPORT PGSSLMODE="${PGSSLMODE:-require}"

if [ "$(psql -U "$PGUSER" -d "$PGDATABASE" -Atc "SELECT count(*) FROM pg_roles WHERE rolname = 'reservation_owner'")" != "0" ]; then
  echo "Roles already exist in this database: nothing to do (this script is for a fresh database)."
  exit 0
fi

POSTGRES_USER="$PGUSER" POSTGRES_DB="$PGDATABASE" SECRETS_DIR="$PWD/deploy/secrets" \
  bash deploy/compose/postgres/10-roles.sh

echo "Done. Schemas and roles created in database '$PGDATABASE' on $PGHOST."
