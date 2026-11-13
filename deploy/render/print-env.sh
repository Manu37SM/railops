#!/usr/bin/env bash
# Prints the environment variables for ONE RailOps service on Render, ready for
# "Environment > Add from .env" in the Render Dashboard.
#
#   deploy/render/print-env.sh identity        > deploy/render/out/identity.env
#   (services: identity reservation payment notification gateway; or "all" to write deploy/render/out/*.env)
#
# Reads deploy/render/render.env (your dashboard values) and deploy/secrets/* (made by scripts/gen-secrets.sh).
# The output contains SECRETS: paste it into Render, don't commit it (deploy/render/out/ is gitignored).
set -euo pipefail
cd "$(dirname "$0")/../.."

INPUTS="${RENDER_INPUTS:-deploy/render/render.env}"
[ -r "$INPUTS" ] || { echo "Missing $INPUTS: copy deploy/render/render.env.example and fill it in." >&2; exit 2; }
[ -d deploy/secrets ] || { echo "Missing deploy/secrets: run scripts/gen-secrets.sh first." >&2; exit 2; }
set -a; . "$INPUTS"; set +a

secret() {
  local f="deploy/secrets/$1"
  [ -s "$f" ] || { echo "Missing secret $f (re-run scripts/gen-secrets.sh)." >&2; exit 2; }
  tr -d '\n' < "$f"
}
# dotenv line; values with spaces or ; | are double-quoted (Render's "Add from .env" strips the quotes).
kv() {
  case "$2" in
    *[\ \;\|]*) printf '%s="%s"\n' "$1" "$2" ;;
    *) printf '%s=%s\n' "$1" "$2" ;;
  esac
}
require() { for v in "$@"; do [ -n "${!v:-}" ] || { echo "Set $v in $INPUTS" >&2; exit 2; }; done; }

# Fits a 512 MB instance: measured ~300 MB RSS for reservation-service under load with these flags.
JVM="-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=160m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8"

common() {   # every service
  kv PORT 8080
  kv SERVER_PORT 8080
  kv JAVA_TOOL_OPTIONS "$JVM"
  # Without the k8s network policy, /actuator/prometheus would be public on Render. Health only.
  kv MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE health
  kv SPRINGDOC_ENABLED false
  kv JWT_ISSUER "${IDENTITY_URL}"
}

database() {   # $1 = schema/service short name
  require NEON_HOST NEON_DB
  # DIRECT connection: RailOps uses session advisory locks and SET, which Neon's pooled (PgBouncer
  # transaction-mode) endpoint does not support.
  kv DB_URL "jdbc:postgresql://${NEON_HOST}/${NEON_DB}?sslmode=require"
  kv DB_USER "$1_app"
  kv DB_PASSWORD "$(secret "$1_app_db_password")"
  # Flyway migrates at startup as the schema OWNER; the app itself runs as the DML-only app role.
  kv SPRING_FLYWAY_ENABLED true
  kv DB_MIGRATION_USER "$1_owner"
  kv DB_MIGRATION_PASSWORD "$(secret "$1_owner_db_password")"
  # Neon's smallest compute allows ~100 connections in total; 5 services share it.
  kv DB_POOL_SIZE 5
}

redis() {   # gateway + reservation
  require REDIS_HOST
  kv REDIS_HOST "$REDIS_HOST"
  kv REDIS_PORT "${REDIS_PORT:-6379}"
  # An outside Redis (Redis Cloud / Upstash) needs a password; Render's internal Key Value has none.
  if [ -n "${REDIS_PASSWORD:-}" ]; then
    kv SPRING_DATA_REDIS_USERNAME "${REDIS_USERNAME:-default}"
    kv SPRING_DATA_REDIS_PASSWORD "$REDIS_PASSWORD"
  fi
  if [ "${REDIS_SSL:-false}" = true ]; then
    kv SPRING_DATA_REDIS_SSL_ENABLED true
  fi
}

kafka() {
  require KAFKA_BOOTSTRAP
  kv KAFKA_BOOTSTRAP "$KAFKA_BOOTSTRAP"
  kv KAFKA_REPLICAS 3
  kv RAILOPS_KAFKA_REPLICAS 3
  if [ -n "${KAFKA_API_KEY:-}" ]; then
    # Confluent Cloud: SASL_SSL + PLAIN with the cluster API key. Spring Boot's spring.kafka.jaas.* builds
    # the JAAS config, so no quoting games with sasl.jaas.config in an environment variable.
    require KAFKA_API_SECRET
    kv SPRING_KAFKA_SECURITY_PROTOCOL SASL_SSL
    kv SPRING_KAFKA_PROPERTIES_SASL_MECHANISM PLAIN
    kv SPRING_KAFKA_JAAS_ENABLED true
    kv SPRING_KAFKA_JAAS_LOGINMODULE org.apache.kafka.common.security.plain.PlainLoginModule
    kv SPRING_KAFKA_JAAS_OPTIONS_USERNAME "$KAFKA_API_KEY"
    kv SPRING_KAFKA_JAAS_OPTIONS_PASSWORD "$KAFKA_API_SECRET"
  fi
}

resource_server() {   # services that verify user tokens
  require IDENTITY_URL
  kv JWK_SET_URI "${IDENTITY_URL}/.well-known/jwks.json"
}

service_client() {   # $1 = client id
  require IDENTITY_URL
  kv TOKEN_URI "${IDENTITY_URL}/oauth2/token"
  kv SERVICE_CLIENT_ID "$1"
  kv SERVICE_CLIENT_SECRET "$(secret "$1_client_secret")"
}

print_service() {
  case "$1" in
    identity)
      require WEB_URL BOOTSTRAP_ADMIN_EMAIL
      common; database identity; kafka
      kv ALLOWED_ORIGINS "$WEB_URL"
      kv FIELD_KEY_ID 1
      kv FIELD_ENC_KEY "$(secret field_enc_key)"
      kv PHONE_HMAC_KEY "$(secret phone_hmac_key)"
      kv SERVICE_CLIENTS "$(secret service_clients)"
      kv BOOTSTRAP_ADMIN_EMAIL "$BOOTSTRAP_ADMIN_EMAIL"
      kv BOOTSTRAP_ADMIN_PASSWORD "$(secret bootstrap_admin_password)"
      # Moving RailLens accounts into this login (Phase 4C).
      kv RAILOPS_RAILLENS_BASE_URL "${RAILLENS_API_URL:-https://raillens-train-db.onrender.com/api/v1}"
      echo "# ALSO: add a Secret File named railops.jwt.private-key-pem with the contents of deploy/secrets/jwt_private_key.pem" ;;
    reservation)
      require PAYMENT_URL REDIS_HOST
      common; database reservation; kafka; resource_server; service_client reservation
      # "sim" = built-in fare table (there is no catalog-service yet).
      kv SPRING_PROFILES_ACTIVE sim
      # Phase 5/6: with the simulated gateway, passengers finish its simulated checkout through reservation
      # (POST /bookings/{pnr}/simulated-payment). Off with Razorpay: passengers pay on Razorpay's (test) page.
      kv RAILOPS_PAYMENTS_SIMULATED "$([ "${PAYMENT_GATEWAY:-sim}" = razorpay ] && echo false || echo true)"
      kv PAYMENT_URL "$PAYMENT_URL"
      # Staff import train runs from RailLens timetables (Phase 3B).
      kv RAILOPS_RAILLENS_BASE_URL "${RAILLENS_API_URL:-https://raillens-train-db.onrender.com/api/v1}"
      redis
      kv CONTACT_ENC_KEY "$(secret contact_enc_key)"
      kv CONTACT_HMAC_KEY "$(secret contact_hmac_key)" ;;
    payment)
      common; database payment; kafka; resource_server
      if [ "${PAYMENT_GATEWAY:-sim}" = razorpay ]; then
        # Phase 6: Razorpay TEST MODE (fake cards/UPI, no real money; live keys are refused at startup).
        require RAZORPAY_KEY_ID RAZORPAY_KEY_SECRET RAZORPAY_WEBHOOK_SECRET
        kv SPRING_PROFILES_ACTIVE razorpay
        kv RAZORPAY_KEY_ID "$RAZORPAY_KEY_ID"
        kv RAZORPAY_KEY_SECRET "$RAZORPAY_KEY_SECRET"
        kv RAZORPAY_WEBHOOK_SECRET "$RAZORPAY_WEBHOOK_SECRET"
        kv RAZORPAY_CALLBACK_URL "${RAILLENS_WEB_ORIGIN:-https://raillens.vercel.app}/bookings/{pnr}"
      else
        # SIMULATED payment gateway: fake money only.
        kv SPRING_PROFILES_ACTIVE sim
        kv RAILOPS_SIMGATEWAY_WEBHOOKURL "http://localhost:8080/webhooks/simgw"
      fi
      kv GATEWAY_WEBHOOK_SECRET "$(secret webhook_secret)" ;;
    notification)
      require RESERVATION_URL
      common; database notification; kafka; service_client notification
      kv RESERVATION_URL "$RESERVATION_URL" ;;
    gateway)
      require IDENTITY_URL RESERVATION_URL PAYMENT_URL REDIS_HOST
      kv PORT 8080
      kv SERVER_PORT 8080
      kv JAVA_TOOL_OPTIONS "$JVM"
      # Actuator on the main port (Render routes one port), health only.
      kv MANAGEMENT_PORT 8080
      kv MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE health
      kv IDENTITY_URL "$IDENTITY_URL"
      kv RESERVATION_URL "$RESERVATION_URL"
      kv PAYMENT_URL "$PAYMENT_URL"
      kv JWK_SET_URI "${IDENTITY_URL}/.well-known/jwks.json"
      redis
      # Render's internal proxy. The real visitor IP is in True-Client-IP (set by Cloudflare in front of Render);
      # walking X-Forwarded-For would stop at a Cloudflare edge address and put everyone in one rate-limit bucket.
      kv RAILOPS_EDGE_TRUSTED_PROXIES "10.0.0.0/8"
      kv RAILOPS_EDGE_CLIENT_IP_HEADER "True-Client-IP"
      # The RailLens website may call the RailOps API from the browser (booking, Phase 4). Optional.
      kv RAILOPS_EDGE_CORS_ALLOWED_ORIGINS "${RAILLENS_WEB_ORIGIN:-}"
      # Phase 4D: the RailLens website signs people in through its own server; with this shared secret it may name
      # the visitor's IP, so login rate limits stay per visitor. Give the same value to the website's
      # RAILOPS_PROXY_SECRET (Vercel). Printed only when the secret file exists (gen-secrets.sh creates it).
      if [ -s deploy/secrets/raillens_proxy_secret ]; then kv RAILOPS_EDGE_PROXY_SECRET "$(secret raillens_proxy_secret)"; fi ;;
    *) echo "unknown service '$1' (identity reservation payment notification gateway all)" >&2; exit 2 ;;
  esac
}

if [ "${1:-}" = all ]; then
  mkdir -p deploy/render/out
  for s in identity reservation payment notification gateway; do
    print_service "$s" > "deploy/render/out/$s.env"
    echo "wrote deploy/render/out/$s.env"
  done
else
  print_service "${1:?usage: print-env.sh <identity|reservation|payment|notification|gateway|all>}"
fi
