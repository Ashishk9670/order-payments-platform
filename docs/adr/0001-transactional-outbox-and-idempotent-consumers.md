# 1. Transactional outbox and idempotent consumers

Status: accepted

## Context
A service must change its database and tell the others about it. Writing to the database and publishing to Kafka are two systems; a crash between them either loses the event or announces something that never committed.

## Decision
Events are written to an `outbox_event` table in the **same transaction** as the state change and relayed to Kafka by a poller (`FOR UPDATE SKIP LOCKED`). Delivery is at-least-once, so every consumer records handled event ids in `processed_event` in the same transaction as its effect (inbox). The trace context is stored with the row so traces survive the hop.

## Consequences
* No lost or phantom events, even across crashes and broker outages.
* Consumers must tolerate duplicates (they do, and it is tested).
* Publishing latency is the poll interval (500 ms by default); Debezium CDC can replace the poller when that matters (`OUTBOX_RELAY_ENABLED=false`).

## Alternatives considered
* Publish directly after commit: loses events on a crash.
* Kafka transactions plus database XA: heavy, couples the availability of both.
* Event sourcing: a far larger change than the problem needs.
