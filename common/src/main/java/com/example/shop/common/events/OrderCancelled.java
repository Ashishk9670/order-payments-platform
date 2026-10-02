package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelled(UUID eventId, UUID orderId, String customerId, String reason,
                             Instant occurredAt) implements DomainEvent {

    public static OrderCancelled of(UUID orderId, String customerId, String reason) {
        return new OrderCancelled(UUID.randomUUID(), orderId, customerId, reason, Instant.now());
    }
}
