# Service down

**What it means.** Prometheus cannot scrape a service for 2 minutes.

**Customer impact.** Depends on the service: gateway/order-service down = customers cannot order; inventory/payment/catalog down = orders queue up in Kafka (nothing is lost) or are rejected with 503 (catalog); notification down = no notifications.

## Triage
1. `kubectl get pods -l app.kubernetes.io/instance=orders` (or `docker compose ps`): is it crash-looping or unscheduled?
2. `kubectl logs <pod> --previous --tail=200`: look for `APPLICATION FAILED TO START` (bad config, missing secret, database unreachable, Flyway error).
3. `kubectl describe pod <pod>`: `OOMKilled` means the memory limit is too low.

## Fix
* Bad config or secret: fix the value and `helm upgrade`.
* Flyway migration failure: do **not** edit an applied migration; add a new one. Check `flyway_schema_history` in that service's database.
* OOMKilled: raise `services.<name>.resources.limits.memory` and look at the heap panel for a leak.
* Dependency down (Postgres, Kafka, Redis): fix that first; services reconnect on their own.

## Escalate when
The service cannot start after a rollback to the previous release (`helm rollback orders`).
