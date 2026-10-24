# Circuit breaker open

**What it means.** A circuit breaker (catalog in the order service, card processor in the payment service) opened after repeated failures.

**Customer impact.** Catalog: order placement returns 503. Processor: payments and refunds are declined with "Payment provider unavailable" (orders are cancelled cleanly and stock is released; refunds leave the order CONFIRMED).

## Triage
1. Which breaker? Label `name` (`catalog` or `psp`).
2. Check that dependency directly (its health endpoint or the processor status page).
3. The breaker half-opens by itself after 10 s (catalog) / 15 s (processor) and closes once calls succeed.

## Fix
Fix or wait out the dependency; no action on our side is needed once it recovers.

## Escalate when
The dependency is healthy but the breaker stays open (check timeouts: `catalog.timeout-ms`, `psp.timeout-ms`).
