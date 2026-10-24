# 6. OAuth2 resource server with per-customer isolation

Status: accepted

## Context
Customers must only see and change their own orders; operators need wider access; internal service APIs must not be open.

## Decision
Every service validates JWTs. Tokens come from an identity provider (a Keycloak profile is included) in production, or from a shared HS256 secret plus a dev-only token endpoint locally and in CI. Customers see only their own orders (another customer's order is a 404, so ids cannot be probed); `ADMIN` sees all; service-to-service calls carry a service token. The same rules apply to REST and GraphQL.

## Consequences
* One authorization rule set, tested in unit, integration and end-to-end suites.
* The dev token endpoint and shared secret exist only for local and CI use and are disabled by default in the Helm chart.
