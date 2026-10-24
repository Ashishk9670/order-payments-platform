# High latency

**What it means.** p95 request latency above 1 second for 10 minutes.

**Customer impact.** Slow order placement and page loads.

## Triage
1. Which service and endpoint? Grafana "HTTP latency p95".
2. Database: the "DB connections pending" panel; slow queries in the Postgres log.
3. Downstream: catalog latency (order placement calls it synchronously).
4. CPU throttling: `kubectl top pod`; HPA at its maximum?

## Fix
* Pool exhausted: see db-pool.md.
* Saturated CPU: raise the HPA maximum or the CPU request.
* Slow catalog: check its database; prices are cached in Redis for 30 seconds, so a cold cache after a Redis restart causes a burst of catalog calls.

## Escalate when
Latency stays high after scaling out, which points to a code or query problem.
