package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

/** Every event carries a unique id (for consumer idempotency) and the order it belongs to (the Kafka key). */
public interface DomainEvent {
    UUID eventId();

    UUID orderId();

    Instant occurredAt();

    default String eventType() {
        return getClass().getSimpleName();
    }
}
