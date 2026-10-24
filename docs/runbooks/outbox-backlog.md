# Outbox backlog / publish failures

**What it means.** `outbox_pending` is high or `outbox_publish_failures_total` is increasing: events are written to the database but not reaching Kafka.

**Customer impact.** Orders are accepted but do not progress (they stay PENDING). Nothing is lost: events stay in the outbox and are published once Kafka is back.

## Triage
1. Is Kafka reachable? `kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`.
2. Count the backlog per service: `SELECT count(*) FROM outbox_event WHERE published_at IS NULL;` (and `min(created_at)` for the oldest).
3. Relay errors: `kubectl logs <pod> | grep "Outbox publish failed"`.
4. If Debezium CDC is in use (`OUTBOX_RELAY_ENABLED=false`), check the connector instead: `curl $CONNECT_URL/connectors?expand=status`.

## Fix
* Kafka down or unreachable: restore it; the relay drains the backlog automatically (oldest first, per order ordering is preserved).
* A single poison row cannot block the relay because publishing is per event; if one event fails repeatedly, read its error in the log (for example an oversized message).
* After recovery the consumers receive a burst; consumer lag may rise briefly (see consumer-lag.md).

## Escalate when
The backlog keeps growing while Kafka is healthy.
