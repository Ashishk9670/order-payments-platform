# Order & Payments Platform

Event-driven order processing: Java 21, Spring Boot 3.5, Kafka, Redis, PostgreSQL, REST, GraphQL (queries, mutations,
live subscriptions), an AsyncAPI contract, OAuth2/JWT security, an API gateway, tracing, metrics and alerts, and a Helm chart.

```
                      browser / API clients
                              │  (rate limiting, request ids)
                      ┌───────▼────────┐
                      │  api-gateway   │ :8080       only this is exposed publicly
                      └───────┬────────┘
                              │ REST + GraphQL + SSE + storefront UI
                      ┌───────▼────────┐  catalog lookup (HTTP, circuit breaker)  ┌─────────────────┐
                      │ order-service  ├─────────────────────────────────────────►│ catalog-service │ :8085
                      │    :8081       │                                          └─────────────────┘
                      └───┬─────▲──────┘
        order.created     │     │ inventory.reserved/failed, payment.authorized/failed, payment.refunded/refund-failed
        order.cancellation│     │
        -requested        ▼     │
                  ┌──────────────┴───┐  inventory.reserved   ┌──────────────────┐   charges / refunds
                  │ inventory-service├──────────────────────►│ payment-service  ├──────────────► card processor
                  │      :8082       │◄──────────────────────┤     :8083        │   (simulated, or HTTP adapter)
                  └──────────────────┘  payment.failed /     └──────────────────┘
                                        payment.refunded
                  order.confirmed / order.cancelled ──► notification-service :8084
```

Each service owns its PostgreSQL database. Services talk through Kafka events (and the one synchronous call to the
catalog); nothing imports another service's code, and an ArchUnit test enforces that.
Why things are the way they are: [`docs/adr`](docs/adr). Operating it: [`docs/runbooks`](docs/runbooks) and [`docs/slo.md`](docs/slo.md).

## What it does

**Order saga (choreography).** `OrderCreated → InventoryReserved → PaymentAuthorized → OrderConfirmed`.
A stock shortage or a declined payment cancels the order; a declined payment also makes inventory release the stock.

**Refund saga.** Cancelling a *confirmed* order moves it to `REFUND_PENDING`, payment refunds, inventory restocks,
then the order becomes `CANCELLED`. If the processor refuses the refund, the order goes back to `CONFIRMED`.

**Prices are server-side.** The client sends SKUs and quantities only; `catalog-service` owns prices, and a client-supplied
price is ignored (covered by a test).

## Design decisions

| Concern | Approach |
|---|---|
| Atomic DB write + event publish | **Transactional outbox** (`common/outbox`): the event row is saved in the same transaction and relayed to Kafka by a poller (`FOR UPDATE SKIP LOCKED`, so several instances can relay). At-least-once. Optional Debezium CDC replaces the poller (see below). |
| Duplicate delivery | **Inbox / idempotent consumer** (`common/inbox`): a `processed_event` insert in the same transaction as the business effect. |
| Event ordering across topics | None is assumed. Example: `PaymentAuthorized` may overtake `InventoryReserved`, so `confirm()` is accepted from `PENDING`; found by an integration test, fixed and unit-tested. |
| Poison messages | Exponential-backoff retries, then `<topic>.DLT`. Malformed payloads are non-retryable and go straight to the DLT without blocking the partition (integration-tested). |
| Ordering | Order id is the Kafka key, so one order's events stay in one partition. |
| Overselling | Atomic `UPDATE … WHERE available >= qty` is the guarantee; a Redis lock (sorted keys, token-checked release) only reduces contention. Multi-line orders are all-or-nothing. |
| Duplicate client requests | `Idempotency-Key` header (`SET NX` in Redis, **scoped per customer**): `202` first time, `200` replay, `409` while in flight. |
| AuthN / AuthZ | OAuth2 resource server. Customers see and cancel **only their own** orders (someone else's order is a `404`, so ids cannot be probed); `ADMIN` sees all. Same rules in REST and GraphQL. |
| Resilience | Circuit breaker + retry around the catalog and the card processor (Resilience4j). A processor outage becomes a *decline*, so the saga compensates instead of hanging; processor calls carry an idempotency key so a retry can never double-charge; an unreadable 2xx reply is retried with the same key, never treated as a decline. |
| Read performance | Redis cache on `GET /orders/{id}` (30 s), evicted **after commit** on status change. |
| Abuse protection | Per-caller token bucket in the gateway (Redis) plus a per-IP limiter in the order service (`429` + `Retry-After`). |
| GraphQL | `@BatchMapping` removes N+1 (N orders cost 2 SQL statements); mutations reuse the REST services and rules; `orderStatusChanged` is a **subscription over SSE**, fanned out across instances via Redis pub/sub. |
| Errors | RFC 7807 `application/problem+json` everywhere, including 401/403. |
| Schema | Flyway; Hibernate `ddl-auto: validate`. |
| Event contracts | JSON Schemas in `common/src/main/resources/schemas`, a backward-compatibility checker against the released baseline, and a test that fails if `docs/asyncapi.yaml` drifts from the code. |

## Run it

Prerequisites: JDK 21+, Maven, Docker. `make help` lists the shortcuts (`make up`, `make test`, `make e2e` ...).

```bash
docker compose --profile apps up --build -d
./scripts/wait-for-stack.sh
open http://localhost:8080            # storefront through the gateway (http://localhost:8081 goes direct)
open http://localhost:8081/graphiql   # GraphQL playground
```

Only infrastructure, with the services run from your IDE: `docker compose up -d postgres kafka redis`.

Get a token and place an order (the compose file enables the **dev-only** `/dev/token` endpoint):

```bash
TOKEN=$(curl -s localhost:8081/dev/token -H 'Content-Type: application/json' -d '{"customerId":"alice"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"customerId":"alice","items":[{"sku":"SKU-1001","quantity":2}]}'
curl -s localhost:8081/api/v1/orders/<id> -H "Authorization: Bearer $TOKEN"     # PENDING -> CONFIRMED in ~2 s
curl -s -X POST localhost:8081/api/v1/orders/<id>/cancel -H "Authorization: Bearer $TOKEN"   # refund saga
```

Demo switches: **`SKU-LUXURY`** is priced above the simulated card limit (10 000) and is declined; `SKU-1003` has 5 units; an unknown SKU is a `422`.

> `inventory`, `payment` and `catalog` expose admin/debug APIs (`restock`, `PUT /products`). They are **internal**: the gateway does not route them and the Helm chart gives them no ingress. Do not expose their ports publicly.

### Ways to run it

| Mode | How | Verified by |
|---|---|---|
| Standard (simulated processor, dev tokens) | `docker compose --profile apps up --build -d` | CI `ci.yml` (unit, integration, e2e) |
| Card processor over HTTP | `PAYMENT_GATEWAY=http docker compose --profile apps --profile psp up -d` (WireMock: amount ≥ 20000 declined, `13.13` → 402, `66.66` → 503, otherwise approved) | CI `modes.yml` → `E2E_PSP=1 npx playwright test --project=psp` |
| Real identity provider | `docker compose -f docker-compose.yml -f docker-compose.keycloak.yml --profile apps --profile keycloak up -d` (realm users `alice`, `bob`, `ops`; the dev token endpoint is off) | CI `modes.yml` → `E2E_KEYCLOAK=1 ... --project=keycloak` |
| Debezium instead of the polling relay | `docker compose -f docker-compose.yml -f docker-compose.cdc.yml --profile apps --profile cdc up -d`, then `POSTGRES_PASSWORD=postgres scripts/register-debezium.sh` | CI `modes.yml` (cdc job) |
| Kubernetes | `deploy/helm/order-platform` (see below) | CI `k8s.yml` |

### Security configuration

| Mode | Settings |
|---|---|
| Local / CI (default in compose) | `JWT_SECRET` (HS256, ≥ 32 bytes) and `DEV_TOKEN_ENABLED=true`. **Dev only.** |
| Keycloak | The overlay above sets `JWT_SECRET=` (empty), `CUSTOMER_ID_CLAIM=preferred_username` and validates signatures against the realm's JWKS endpoint (containers and browsers reach Keycloak under different host names, so issuer discovery would see two different `iss` values). In production use one public issuer URL and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`. Realm import: `infra/keycloak/realm-orders.json`. |
| Production | An issuer URI, `DEV_TOKEN_ENABLED=false`, secrets from your secret store (the Helm chart only references existing Secrets). |

The shared secret and the realm's user passwords in this repository are development placeholders; never reuse them.

## Tests

| Layer | What it covers | Command | Needs |
|---|---|---|---|
| Unit | domain state machines, saga handlers, outbox relay/tracing, inbox, resilience (against real local HTTP servers), controllers (MockMvc), GraphQL layer, gateway routes | `mvn test` | JDK |
| Architecture | no cross-service imports, pure domain packages, no package cycles, constructor injection only (ArchUnit) | `mvn test` | JDK |
| Contract | event JSON Schemas vs what the code emits, compatibility vs baseline, AsyncAPI sync | `mvn test` | JDK |
| Coverage gate | per-module line-coverage floor (JaCoCo) | `mvn verify -DskipITs` | JDK |
| Integration | each service against real Postgres + Kafka + Redis (Testcontainers): outbox to Kafka, saga steps, redelivery, DLT, concurrent no-oversell, refund, GraphQL, rate limit, security | `mvn verify` | Docker |
| E2E API | the full saga through the real stack, refund saga, security/isolation, gateway, catalog, GraphQL incl. SSE subscription | `cd e2e && npm ci && npx playwright test --project=api` | running stack |
| E2E UI | storefront in Chromium: flows, validation, live updates vs polling fallback, accessibility (axe, light + dark), keyboard, phone viewport, through the gateway | `npx playwright test --project=ui` | running stack |
| E2E modes | HTTP card processor, Keycloak, Debezium (see "Ways to run it") | `E2E_PSP=1` / `E2E_KEYCLOAK=1` | matching stack |
| Kubernetes | chart install on kind, full suites against it, zero dropped requests during a rolling update | `scripts/k8s-rollout-check.sh` | cluster |
| Load | k6 against the real stack with latency and saga-completion thresholds | `k6 run load/orders.js` | running stack + k6 |

First time for Playwright: `cd e2e && npm ci && npx playwright install chromium`. For a bulk run start the stack with
`RATE_LIMIT=100000` (the suite sends many requests from one IP); set `E2E_SKIP_GATEWAY_RATELIMIT=1` when the gateway burst is raised.

## CI/CD (`.github/workflows`)

* **ci.yml** — `unit` (tests, architecture, contracts, coverage gate), `helm` (lint + render + unsafe-config rejection),
  `integration` (Testcontainers), `e2e` (docker compose + Playwright; the report is uploaded).
* **modes.yml** — the HTTP card processor, Keycloak and Debezium variants of the stack, each with its own end-to-end suite.
* **k8s.yml** — installs the chart on a kind cluster, runs the suites against it and checks a rolling update drops nothing.
* **release.yml** — builds and publishes one image per service to GHCR for commits whose CI passed (and for `v*.*.*` tags), with provenance and SBOM. Only for pushes to this repository, never fork pull requests.
* **codeql.yml** — CodeQL for Java and TypeScript on push/PR and weekly. **dependabot.yml** — grouped minor/patch updates for Maven, npm, Actions and Docker (majors are left to a human).
* **load.yml** — nightly k6 run that fails on threshold breaches.

## Observability

Every service exposes Prometheus metrics (`/actuator/prometheus`) and exports OpenTelemetry traces. The trace context is
stored with each outbox row and restored by the relay, so **one trace spans the HTTP request, the outbox hop and every
Kafka consumer** (verified in Jaeger: a single order is one trace over four services).

```bash
TRACING_SAMPLING=1.0 docker compose --profile apps --profile observability up --build -d
# Jaeger http://localhost:16686   Prometheus http://localhost:9091   Alertmanager http://localhost:9093
# Grafana http://localhost:3000 (GRAFANA_PORT=3001 if 3000 is taken), dashboard "Order Platform"
```

Custom metrics worth alerting on: `outbox_pending` (events written but not yet in Kafka), `outbox_publish_failures_total`,
`orders_transitions_total{status}`, and the Resilience4j circuit-breaker state. The dashboard shows request rate, p95 latency, 5xx,
Kafka consumer lag, DB pool pressure and JVM heap; its queries were run against a live Prometheus (17 of 18 return data, the
remaining one is the 5xx panel, empty when nothing fails). The 12 alert rules in `infra/observability/alerts.yml` are
validated with `promtool`, and each links to a runbook in [`docs/runbooks`](docs/runbooks). Targets: [`docs/slo.md`](docs/slo.md).

## Deployment (Kubernetes)

`deploy/helm/order-platform` deploys the six services (not Postgres/Kafka/Redis; point at yours via `global.*`):
hardened pods (non-root, read-only root filesystem, dropped capabilities, seccomp), startup/liveness/readiness probes,
zero-downtime rolling updates, HPAs, PodDisruptionBudgets, NetworkPolicies (only the gateway is reachable from outside), and
an Ingress for the gateway only. Secrets are referenced from existing Kubernetes Secrets, never stored in values.

```bash
helm lint deploy/helm/order-platform --strict
helm upgrade --install orders deploy/helm/order-platform \
  --set ingress.enabled=true --set ingress.host=orders.example.com \
  --set security.issuerUri=https://idp.example.com/realms/orders \
  --set global.postgres.existingSecret=order-platform-db
```

Misconfiguration is rejected at render time (for example `payment.gateway=http` without `payment.psp.url`, or TLS without a secret name).

**Try it locally** on kind with throwaway infrastructure: build the images (`docker compose build`), retag them as `local/<service>:dev`,
`kind load docker-image ...`, `kubectl apply -f deploy/local/infra.yaml`, create the `orders-jwt` secret, then
`helm install orders deploy/helm/order-platform -f deploy/local/values-kind.yaml`. This was done for real: all pods ready with zero restarts,
92 of 95 suite tests passing (the 3 were setup-specific and fixed), and 124 of 124 requests succeeding during a full rolling restart.
kind's default network plugin does not enforce NetworkPolicies, so the policies are applied but not exercised there.

## Debezium CDC instead of the polling relay

`docker-compose.cdc.yml` turns the polling relay off (`OUTBOX_RELAY_ENABLED=false`) and CDC mode on (`OUTBOX_CDC_ENABLED=true`: the row
is deleted right after the insert, the database log still carries it). Connector definitions are in `infra/debezium`.
The published messages carry the order id as key, the event type and the **trace context as headers**, and the JSON payload.

**Order of operations matters.** Register the connectors *before* enabling CDC mode. The replication slot keeps changes while the connector is
down, but changes made before the slot exists are not captured, and in CDC mode the outbox row is already gone. Switching from the
polling relay is safe because rows still in the table are picked up by the connector's initial snapshot.

## Contracts

* REST: `/api/v1/orders`, `/api/v1/products` (via the order service), plus the internal inventory/payment/catalog APIs.
* GraphQL: `/graphql` (schema in `order-service/src/main/resources/graphql`).
* Events: [`docs/asyncapi.yaml`](docs/asyncapi.yaml) and the JSON Schemas in `common/src/main/resources/schemas`.

## Known limits

* JSON event schemas with compatibility checks, **not** Avro/Protobuf with a schema registry ([ADR 4](docs/adr/0004-json-events-with-json-schema-contracts-instead-of-avro-and-a-schema-registry.md)).
* The card processor is simulated by default; the HTTP adapter is tested against a local fake and WireMock, not a real processor sandbox.
* Inventory, payment and catalog admin endpoints are unauthenticated and rely on network isolation (see "Design decisions").
* A refund releases stock back to inventory; shipping and fulfilment are out of scope; notifications are logged, not sent.

## License

[MIT](LICENSE). The development placeholders in this repository (the `postgres` password, the dev JWT secret, the Keycloak realm users, the Grafana admin password) are for local use only; never reuse them anywhere real.
