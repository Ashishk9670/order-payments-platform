# 7. The gateway exposes only what customers need

Status: accepted

## Context
Inventory, payment and catalog administration endpoints are internal tools.

## Decision
The gateway routes the storefront, order API, GraphQL and the catalog read-through to the order service. The internal services are not routed and the chart gives them no ingress (NetworkPolicies allow only in-release traffic). They additionally require an `ADMIN` or service token.

## Consequences
* A mistake in network configuration alone does not expose admin endpoints (defence in depth).
