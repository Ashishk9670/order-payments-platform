#!/usr/bin/env bash
# Blocks until every service answers /actuator/health with UP (or fails after TIMEOUT seconds).
set -euo pipefail
TIMEOUT="${TIMEOUT:-240}"
ports=(8080 8081 8082 8083 8084 8085 8086)
deadline=$((SECONDS + TIMEOUT))
for port in "${ports[@]}"; do
  until curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    if (( SECONDS > deadline )); then
      echo "Timed out waiting for service on :${port}" >&2
      exit 1
    fi
    sleep 3
  done
  echo "service on :${port} is UP"
done
