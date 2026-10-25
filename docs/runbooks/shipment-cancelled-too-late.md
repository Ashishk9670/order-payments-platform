# A cancellation arrived after the parcel was dispatched

**What it means.** A customer asked to cancel a confirmed order in the short window before the order service heard that the
parcel had shipped. The order moved to `REFUND_PENDING`/`CANCELLED` and the refund went through, but the parcel is already
with the carrier (`shipments_cancel_too_late_total` went up; the shipping service logged the order id).

**Customer impact.** The customer is refunded and a parcel is still on its way.

## Triage
1. Find the order: the shipping service log line says `Order <id> was cancelled but its shipment is already SHIPPED`.
2. `GET /api/v1/shipments/order/<id>` (admin token, shipping service) gives the carrier and tracking number.

## Fix
* Ask the carrier to intercept or return the parcel, or contact the customer to let them keep it or refuse delivery.
* Nothing in the platform needs repairing: the order and payment are consistent. This is an operations follow-up.

## Escalate when
It happens repeatedly: the window is too wide. Check consumer lag on the order service (`consumer-lag.md`); a lagging order
consumer delays the "shipped" event and widens the race.
