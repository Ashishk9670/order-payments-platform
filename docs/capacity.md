# Capacity notes

Measured with `load/orders.js` (k6) against the docker-compose stack on one developer laptop (Docker VM, every service one
replica, Postgres/Kafka/Redis single node). These are **relative** numbers for finding the shape of the system, not a promise
for production hardware. Re-run them on your own environment before sizing anything.

| Arrival rate | Orders placed | `POST /orders` p95 / p99 | Confirmed within 20 s | Confirm time p95 | Verdict |
|---|---|---|---|---|---|
| 30 /s | 1,719 | 10 ms / <100 ms | 100 % | 1.5 s | within targets |
| 120 /s | 6,899 | 10 ms / 77 ms | 99.93 % | 11 s | **API fine, saga falls behind** (target: p95 < 5 s) |

## What this says

- **Accepting an order is cheap and stays cheap.** The request path is one local transaction (order + outbox row); it does not
  wait for Kafka, inventory or payment, so latency did not move when the rate quadrupled.
- **The asynchronous saga is the limit.** An order needs the outbox relay plus three Kafka hops and two database transactions to
  reach CONFIRMED. At 30 orders/s that takes ~1.5 s; at 120/s the backlog grows and the tail reaches ~11 s, yet nothing is lost or
  duplicated (99.93 % still confirmed inside the 20 s poll window). Customers see a longer PENDING, not errors.
- So the number to watch is not HTTP latency but **order confirm time** (SLO in `slo.md`) and `outbox_pending`.

## What to try first when confirm time grows (hypotheses, not yet measured)

1. Add replicas of `inventory-service`/`payment-service` and raise topic partitions together (consumers cannot outnumber partitions).
2. Shorten the outbox poll interval or raise its batch size (`OUTBOX_*` settings), or switch to Debezium (CDC) so publishing is
   not poll-bound.
3. Check the Hikari pool size of the busiest service and Postgres CPU; the saga is write-heavy (outbox + inbox rows per hop).

Change one thing, re-run the same k6 command, and record the table row here.

```
RATE=120 DURATION=40s k6 run load/orders.js     # needs DEV_TOKEN_ENABLED and RATE_LIMIT / GATEWAY_RATE_* raised
```
