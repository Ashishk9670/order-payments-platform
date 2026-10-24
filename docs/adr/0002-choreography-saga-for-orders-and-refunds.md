# 2. Choreography saga for orders and refunds

Status: accepted

## Context
Placing an order spans inventory, payment and the order itself, each with its own database. A distributed transaction is not available.

## Decision
Services react to each other's events (`OrderCreated -> InventoryReserved -> PaymentAuthorized -> OrderConfirmed`), with compensating events for failures (stock release, order cancellation, refunds). Events on different topics have no relative ordering guarantee, so each handler relies only on the order's current state, never on arrival order (for example `PaymentAuthorized` is accepted before `InventoryReserved`).

## Consequences
* No central coordinator to scale or fail; services stay independent.
* The flow is spread across services, so it is documented in the README and `docs/asyncapi.yaml` and traced end to end (one trace spans the outbox hop).
* Stuck sagas are caught by a reconciliation monitor instead of a coordinator.

## Alternatives considered
* Orchestration (one coordinator service): easier to read, but a single point of coupling for every flow.
