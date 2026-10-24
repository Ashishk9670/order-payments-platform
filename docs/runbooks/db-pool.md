# Database connection pool saturated

**What it means.** Requests have been waiting for a database connection for 5 minutes.

**Customer impact.** Latency spikes and eventually timeouts.

## Triage
1. Active vs pending connections per service in Grafana.
2. Long-running transactions in Postgres: `SELECT pid, now()-xact_start AS age, query FROM pg_stat_activity WHERE state <> 'idle' ORDER BY age DESC LIMIT 10;`
3. Lock contention in inventory (Redis locks) can hold transactions open.

## Fix
* Short term: scale the service out (each pod has its own pool; keep total connections below Postgres `max_connections`).
* Fix the slow query/transaction. Do not just raise the pool size.

## Escalate when
Connections stay pinned with no slow queries visible (a leak).
