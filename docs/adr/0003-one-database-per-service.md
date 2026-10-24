# 3. One database per service

Status: accepted

## Context
Services must be deployable and changeable independently.

## Decision
Each service owns its PostgreSQL database and schema (Flyway). Nothing reads another service's tables; data crosses boundaries only as events or API calls. An ArchUnit test forbids cross-service imports.

## Consequences
* Independent releases and migrations.
* No cross-service joins: read models are built from events, and the order service asks the catalog for prices over HTTP.
