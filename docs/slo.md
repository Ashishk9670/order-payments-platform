# Service level objectives

Measured from the metrics the services already export; the matching alerts are in `infra/observability/alerts.yml`.

| SLI | Objective | Window | Error budget | Alert |
|---|---|---|---|---|
| Order API availability: share of `POST /api/v1/orders` that do not return 5xx | 99.9 % | 30 days | 43 min of failed requests | `HighErrorRate` |
| Order API latency: p95 of `POST /api/v1/orders` | < 500 ms | 30 days | | `HighLatency` (1 s, as an early warning) |
| Saga completion: order reaches CONFIRMED or CANCELLED | p95 < 5 s, 99.9 % within 1 min | 30 days | | `OrdersStuck`, `OutboxBacklog*`, `KafkaConsumerLag` |
| No lost orders: every accepted order reaches a final state | 100 % | always | none | `OrdersStuck` |

**How to read them.** The "no lost orders" objective is guaranteed by design (transactional outbox, idempotent consumers,
reconciliation of stuck orders), so any breach is an incident, not a budget spend. The k6 test (`load/orders.js`) asserts the
latency and completion objectives on every nightly run.

**Error-budget policy.** If more than half the monthly budget is spent, freeze feature releases until the cause is fixed.
