# Kafka consumer lag

**What it means.** A consumer group is more than 1000 records behind on a topic for 10 minutes.

**Customer impact.** The saga step that this consumer performs is delayed (for example payments or stock reservations), so orders take longer to confirm.

## Triage
1. `kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group <service>` (groups are named after the service).
2. Is the consumer alive and not rebalancing endlessly? Look for `Rebalance` in its log.
3. Is it failing and retrying? Retries use exponential backoff, then the record is parked on `<topic>.DLT` (see dead-letters.md).
4. Slow handler: Jaeger traces of `spring.kafka.listener` spans.

## Fix
* Add partitions/consumers if the handler is healthy but input exceeds capacity (topics have 3 partitions; raise `spring.kafka.listener.concurrency` or the replica count).
* Fix the failing dependency (database, Redis lock contention in inventory).

## Escalate when
Lag grows on all topics at once: suspect the broker.
