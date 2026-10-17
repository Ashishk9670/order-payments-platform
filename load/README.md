# Load tests (k6)

```bash
docker compose --profile apps up --build -d && ./scripts/wait-for-stack.sh   # RATE_LIMIT=100000000 to disable the per-IP limiter
RATE=50 DURATION=2m k6 run load/orders.js
```

What it measures, with the pass/fail targets (edit `thresholds` in `orders.js`):

| Metric | Target | Why |
|---|---|---|
| `place_order` p95 / p99 | < 500 ms / < 1.5 s | the API call itself: one DB transaction plus an outbox row |
| `order_confirm_seconds` p95 | < 5 s | the whole saga as the customer sees it: outbox relay, 3 Kafka hops, 2 DB transactions |
| `orders_confirmed` | > 99 % | the saga must not lose or stall orders under load |
| `http_req_failed` | < 1 % | |
| `dropped_iterations` | 0 | k6 could not start work fast enough = something is saturated |

While it runs, watch the **Order Platform** Grafana dashboard (`docker compose --profile observability up -d`):
*Outbox backlog* and *Kafka consumer lag* show where the pipeline saturates first, and *DB connections pending* shows pool exhaustion.

A nightly run is scheduled in `.github/workflows/load.yml`.
