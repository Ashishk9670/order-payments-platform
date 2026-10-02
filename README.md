# Order & Payments Platform

Event-driven order processing with Java 21, Spring Boot 3.5, Kafka, Redis, PostgreSQL, REST, GraphQL and an AsyncAPI contract.

```
                       REST / GraphQL / UI
                              │
                      ┌───────▼────────┐  order.created   ┌──────────────────┐
                      │ order-service  ├─────────────────►│ inventory-service│
                      │  :8081         │◄─────────────────┤  :8082           │
                      └──▲──────┬──────┘ inventory.reserved/failed └──┬───────┘
                         │      │ order.confirmed/cancelled          │ inventory.reserved
        payment.authorized/failed│                                   ▼
                         │      ▼                            ┌──────────────────┐
                ┌────────┴───┐  ┌──────────────────┐         │ payment-service  │
                │notification│  │  (each service has│         │  :8083           │
                │ :8084      │  │   its own Postgres│         └──────────────────┘
                └────────────┘  │   database)       │   payment.failed → inventory releases stock (compensation)
                                └──────────────────┘
```

**Saga (choreography):** `OrderCreated → InventoryReserved → PaymentAuthorized → OrderConfirmed`.
Failures cancel the order; a payment failure also makes inventory release the reserved stock.

## Design decisions

| Concern | Approach |
|---|---|
| Atomic DB write + event publish | **Transactional outbox** (`common/outbox`): event row saved in the same transaction, relayed to Kafka by a poller using `FOR UPDATE SKIP LOCKED` so several instances can relay safely. At-least-once. |
| Duplicate delivery | **Inbox / idempotent consumer** (`common/inbox`): `processed_event` insert in the same transaction as the business effect. |
| Poison messages | Exponential-backoff retries, then `<topic>.DLT`. Malformed payloads are non-retryable. |
| Ordering | Order id is the Kafka key → one order's events stay in one partition. |
| Overselling | Atomic `UPDATE … WHERE available >= qty` is the correctness guarantee; a Redis lock (sorted keys, token-checked release) only reduces contention. All-or-nothing across lines. |
| Duplicate client requests | `Idempotency-Key` header, `SET NX` in Redis (`202` first time, `200` replay, `409` while in flight). |
| Read performance | Redis cache on `GET /orders/{id}` (30 s TTL), evicted **after commit** on status change. |
| Abuse protection | Redis fixed-window rate limiter on `/api/**` (`429` + `Retry-After`). |
| GraphQL N+1 | `@BatchMapping` for `Order.items`: N orders cost 2 SQL statements. |
| Errors | RFC 7807 `application/problem+json` everywhere. |
| Schema | Flyway; Hibernate `ddl-auto: validate`. |
| Observability | Actuator health/probes, Prometheus metrics at `/actuator/prometheus`. |

## Run it

Prerequisites: JDK 21+, Maven, Docker.

```bash
# whole platform in containers (builds the images)
docker compose --profile apps up --build -d
./scripts/wait-for-stack.sh
open http://localhost:8081            # storefront UI
open http://localhost:8081/graphiql   # GraphQL playground
```

Or only the infrastructure, with services run from your IDE / `mvn spring-boot:run -pl order-service`:

```bash
docker compose up -d postgres kafka redis
```

Try it:

```bash
curl -s -X POST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"customerId":"alice","items":[{"sku":"SKU-1001","quantity":2,"unitPrice":19.99}]}'
curl -s localhost:8081/api/v1/orders/<id>         # PENDING → CONFIRMED within ~2s
curl -s localhost:8082/api/v1/inventory/SKU-1001
curl -s localhost:8083/api/v1/payments/order/<id>
```

Demo switches: a total above **10 000** is declined by the simulated gateway (saga compensation); `SKU-1003` has only 5 units; unknown SKUs are rejected.

## Tests

| Layer | What | Command | Needs |
|---|---|---|---|
| Unit | domain state machine, saga handlers, outbox relay, idempotency, rate limiter, reservation logic, controllers (MockMvc) | `mvn test` | JDK |
| Integration | each service against real Postgres + Kafka + Redis (Testcontainers): outbox → Kafka, saga events, duplicate delivery, concurrent no-oversell, GraphQL, rate limit | `mvn verify` | Docker |
| E2E API | full saga through the real stack: happy path, stock failure, payment decline + compensation, all-or-nothing, concurrency, idempotency, validation, GraphQL | `cd e2e && npm ci && npx playwright test --project=api` | running stack |
| E2E UI | Playwright/Chromium against the storefront: confirm, decline, out of stock, validation, history, double-click | `npx playwright test --project=ui` | running stack |

First time for Playwright: `cd e2e && npm ci && npx playwright install chromium`.
For a bulk run set `RATE_LIMIT=100000` when starting the stack (the suite sends many requests from one IP):

```bash
RATE_LIMIT=100000 docker compose --profile apps up --build -d && ./scripts/wait-for-stack.sh && (cd e2e && npx playwright test)
```

CI (`.github/workflows/ci.yml`): `unit` → `integration` (Testcontainers) in parallel, then `e2e` (docker compose + Playwright, report uploaded as an artifact).
The `.github` folder must be at the root of the Git repository; if you `git init` one level up, move it there.

## Contracts

* REST: `/api/v1/orders`, `/api/v1/inventory`, `/api/v1/payments`
* GraphQL: `/graphql` (schema in `order-service/src/main/resources/graphql`)
* Events: [`docs/asyncapi.yaml`](docs/asyncapi.yaml)

## Not included (deliberate scope limits)

API gateway / service discovery, authentication (add Spring Security + OAuth2 resource server), Avro/Protobuf + schema registry
(JSON today), Debezium CDC (a poller is used instead), Resilience4j around the real PSP call (the gateway is a simulated port),
Kubernetes manifests.
