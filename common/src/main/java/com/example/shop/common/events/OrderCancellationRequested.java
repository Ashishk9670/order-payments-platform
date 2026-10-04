package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

/** Customer asked to cancel a confirmed order: starts the refund saga. */
public record OrderCancellationRequested(UUID eventId, UUID orderId, String reason, Instant occurredAt) implements DomainEvent {

    public static OrderCancellationRequested of(UUID orderId, String reason) {
        return new OrderCancellationRequested(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
