# Stuck orders

**What it means.** Orders have stayed in PENDING / INVENTORY_RESERVED / REFUND_PENDING longer than the threshold (`orders_stuck` gauge).

**Customer impact.** Those customers are waiting. A paid-but-unconfirmed order is the worst case.

## Triage
1. List them (admin): `GET /api/v1/admin/stuck-orders` on the order service: id, status, age.
2. For each, follow the saga: PENDING = inventory has not answered; INVENTORY_RESERVED = payment has not answered; REFUND_PENDING = refund not answered.
3. Check the matching service for the order id: `inventory`/`payment` REST (admin), logs, Jaeger trace by order id.
4. Check for parked messages (dead-letters.md) and consumer lag (consumer-lag.md); these are the usual causes.

## Fix
* Clear the underlying cause; stuck orders then progress on their own (events are replayed or consumed).
* Reservations are released automatically after the configured TTL (`inventory.reservation-ttl`), which cancels the order if payment never arrived. A payment that arrives after that is refunded automatically.

## Escalate when
An order is CONFIRMED in payment (money taken) but not in the order service, and replaying does not fix it.
