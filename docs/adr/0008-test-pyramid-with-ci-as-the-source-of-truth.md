# 8. Test pyramid with CI as the source of truth

Status: accepted

## Context
The system spans many moving parts; the cheapest tests should catch most problems and the expensive ones must prove the whole.

## Decision
Unit tests (including architecture, contract and coverage gates) run everywhere in seconds. Integration tests run each service against real Postgres, Kafka and Redis (Testcontainers). End-to-end tests drive the full stack through the API and a real browser, including accessibility checks. Load and chaos tests have dedicated, non-default workflows.

## Consequences
* Real bugs were found at every layer (a saga ordering race, packaging, a mobile layout problem, unenforced architecture tests), which is the reason to keep all of them.
* Integration and end-to-end tests need Docker; unit tests do not.
