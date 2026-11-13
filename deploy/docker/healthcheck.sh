#!/usr/bin/env bash
# Readiness probe without curl/wget in the image: raw HTTP/1.0 over bash's /dev/tcp.
# HEALTH_PORT lets the gateway point at its separate management port.
set -euo pipefail
port="${HEALTH_PORT:-${SERVER_PORT:-8080}}"
exec 3<>"/dev/tcp/127.0.0.1/${port}"
printf 'GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
response="$(timeout 2 cat <&3)"
[[ "${response}" == "HTTP/1."?" 200"* ]]
