package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record OrderDelivered(UUID eventId, UUID orderId, Instant occurredAt) implements DomainEvent {

    public static OrderDelivered of(UUID orderId) {
        return new OrderDelivered(UUID.randomUUID(), orderId, Instant.now());
    }
}
