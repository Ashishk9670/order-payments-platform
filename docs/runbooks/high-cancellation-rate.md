# High cancellation rate

**What it means.** More than 30% of orders are being cancelled over 15 minutes.

**Customer impact.** Lost sales; usually a symptom of stock or payment trouble.

## Triage
1. Break down by reason: `orders_transitions_total{status="CANCELLED"}`; reasons are in the order `cancelReason` ("Inventory: ..." vs "Payment: ...").
2. "Payment:" dominating: the card processor is declining or unreachable (an unreachable processor is reported as a decline); check the circuit-breaker panel and the processor status page.
3. "Inventory:" dominating: stock is genuinely out (restock) or the catalog lists SKUs that inventory does not have.

## Fix
* Processor outage: nothing to do but wait; orders are cancelled cleanly and stock is released.
* Stock: restock via the inventory admin API.

## Escalate when
The cause is neither stock nor the processor.
