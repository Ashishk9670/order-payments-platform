# 5. Prices are owned by the catalog and applied server-side

Status: accepted

## Context
A client-supplied price lets a customer set their own total.

## Decision
The order API accepts SKUs and quantities only. The order service looks up each price from the catalog service (cached 30 s, protected by a circuit breaker and retry) and ignores any price in the request. An unknown SKU is rejected immediately with 422.

## Consequences
* Prices cannot be tampered with (tested).
* Order placement depends on the catalog: when it is down and prices are not cached, placement returns 503 instead of guessing.
