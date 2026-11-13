#!/usr/bin/env bash
# Generates every secret the compose stack needs into deploy/secrets/ (git-ignored, mode 600). Idempotent: existing
# files are kept, so re-running never rotates a key that already encrypted data. Production secrets live in GCP
# Secret Manager (deploy/k8s); this is for compose / CI only.
set -euo pipefail
cd "$(dirname "$0")/../deploy"
umask 022
mkdir -p secrets
s() { [ -s "secrets/$1" ] || { printf '%s' "$2" > "secrets/$1"; echo "created $1"; }; }
rnd() { openssl rand -base64 "$1" | tr -d '\n'; }
pw()  { openssl rand -base64 33 | tr -d '\n/+=' | cut -c1-32; }

s postgres_superuser_password "$(pw)"
for svc in reservation identity payment notification; do
  s ${svc}_owner_db_password "$(pw)"
  s ${svc}_app_db_password "$(pw)"
done
s ops_readonly_db_password "$(pw)"
s ops_breakglass_db_password "$(pw)"
s redis_password "$(pw)"
s field_enc_key "$(rnd 32)"            # AES-256 for identity PII fields
s phone_hmac_key "$(rnd 32)"
s contact_enc_key "$(rnd 32)"          # AES-256 for reservation contacts
s contact_hmac_key "$(rnd 32)"
s webhook_secret "$(rnd 32)"
s reservation_client_secret "$(pw)"
s notification_client_secret "$(pw)"
s bootstrap_admin_password "$(pw)Aa1!"
s raillens_proxy_secret "$(pw)$(pw)"   # Phase 4D: shared with the RailLens website (64 chars)
[ -s secrets/jwt_private_key.pem ] || { openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out secrets/jwt_private_key.pem 2>/dev/null; echo "created jwt_private_key.pem"; }
# identity registers service clients from this one value: id|secret|scopes;...
s service_clients "reservation|$(cat secrets/reservation_client_secret)|payments:create payments:read bookings:read;notification|$(cat secrets/notification_client_secret)|contacts:read"
# Compose file secrets are BIND MOUNTS that keep host permissions, and each container reads them as its own uid
# (postgres 70, services 10001, redis 999). So: files world-readable, directory 0700 -> no other HOST user can
# reach them, every container can read its own. (0600 files = roles created without passwords; Phase 12.)
chmod 700 secrets
chmod 644 secrets/*
echo "secrets in deploy/secrets ($(ls secrets | wc -l) files). Never commit them."
