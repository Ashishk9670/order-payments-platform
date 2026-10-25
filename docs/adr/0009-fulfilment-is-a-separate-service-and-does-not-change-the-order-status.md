# 9. Fulfilment is a separate service and does not change the order status

Status: accepted

## Context
Orders needed to be shipped and tracked. The order saga already ends at `CONFIRMED` (paid, in stock); physical dispatch happens
later, takes days, is operated by people, and can fail or be late independently of payment.

## Decision
* A new `shipping-service` owns shipments (its own database, like every service). It reacts to `OrderConfirmed` and
  `OrderCancellationRequested` and publishes `OrderShipped` / `OrderDelivered` through its outbox.
* The order keeps its status machine. Shipping progress is a separate, optional attribute (`fulfillment`: none -> SHIPPED -> DELIVERED
  with carrier and tracking number), set when the shipping events arrive. A `CONFIRMED` order stays `CONFIRMED` while it travels.
* Once an order is shipped it cannot be cancelled by the customer: returns are a different process from cancellation and are not modelled.
* Operators (ADMIN) drive the two transitions through an API; there is no carrier integration yet.

## Consequences
* The saga, its refund path, the SSE stream and every existing consumer are untouched; fulfilment cannot break order confirmation.
* Cancellation versus dispatch is decided in two services and therefore has a small race window. The shipping service resolves
  what it can (a pending parcel is cancelled; a cancellation that arrives before the confirmation leaves a tombstone so the late
  confirmation is ignored). A cancellation that arrives after dispatch is counted (`shipments_cancel_too_late`) and logged for an
  operator, because a parcel on its way cannot be recalled by software.
* Adding a `DELIVERED`-triggered process (invoices, review requests) later means consuming `shipment.delivered.v1`.

## Alternatives considered
* More order statuses (`SHIPPED`, `DELIVERED`): simple to read, but every consumer, the SSE completion rule and the refund path would
  have to learn them, and a shipping problem would look like an order problem.
* Shipment data owned by the order service: avoids a service, but couples warehouse operations to the order database and its release cycle.
