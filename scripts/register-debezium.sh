#!/usr/bin/env bash
# Registers one Debezium outbox connector per service database (CDC mode).
# Prerequisites: docker compose --profile cdc up -d kafka-connect (Kafka Connect is published on host port 8089) and the
# apps started with OUTBOX_RELAY_ENABLED=false OUTBOX_CDC_ENABLED=true so the polling relay is off.
# Idempotent: PUT /connectors/<name>/config creates the connector or updates it in place.
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8089}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD (the compose dev default is \"postgres\")}"
DIR="$(cd "$(dirname "$0")/../infra/debezium" && pwd)"

for file in "$DIR"/outbox-*.json; do
  name="$(basename "$file" .json)"
  echo "registering ${name}"
  # the files are {name, config}; the config endpoint wants only the config object
  python3 - "$file" "$POSTGRES_PASSWORD" <<'PY' | curl -fsS -X PUT -H 'Content-Type: application/json' \
      --data @- "${CONNECT_URL}/connectors/${name}/config" > /dev/null
import json, sys
config = json.load(open(sys.argv[1]))["config"]
config["database.password"] = sys.argv[2]
print(json.dumps(config))
PY
done

echo "done; check status with: curl ${CONNECT_URL}/connectors?expand=status"
