# 4. JSON events with JSON Schema contracts instead of Avro and a schema registry

Status: accepted

## Context
Events are a public contract between services and live in Kafka for a long time. They need versioning and compatibility checks.

## Decision
Events are JSON, each with a JSON Schema in `common/src/main/resources/schemas`. Tests verify that what the code emits satisfies the schema, that schema and record fields match, that changes are backward-compatible against a released baseline, and that `docs/asyncapi.yaml` lists exactly the topics in use. Breaking changes ship as a new versioned topic.

## Consequences
* No registry to run, readable messages and simple tooling, with the same guarantee where it matters (CI fails on a breaking change).
* Larger messages than Avro and no enforcement at the broker.
* Revisit (Avro/Protobuf plus a registry) if many independent teams produce events or message volume makes size matter.

## Alternatives considered
* Avro with a schema registry: stronger runtime enforcement, but more infrastructure and a serializer in every service.
