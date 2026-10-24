# High 5xx rate

**What it means.** More than 5% of HTTP requests to a service fail with a 5xx for 5 minutes.

**Customer impact.** Customers see errors placing or reading orders.

## Triage
1. Grafana "HTTP 5xx rate" panel: which service, which endpoint (`http_server_requests_seconds_count{status=~"5.."}` by `uri`).
2. Jaeger: search the service for traces with errors; the failing span names the dependency.
3. `kubectl logs` for the service around the first error.
4. Check the circuit-breaker panel: an open breaker returns 503 by design (see circuit-open.md).

## Fix
* Database or Redis/Kafka unreachable: restore it; services recover without a restart.
* A bad deploy: `helm rollback orders`.
* Catalog unavailable: order placement returns 503 "Pricing is temporarily unavailable"; fix the catalog.

## Escalate when
Errors continue after a rollback, or any data inconsistency is suspected.
