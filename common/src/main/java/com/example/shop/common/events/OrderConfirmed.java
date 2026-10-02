package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record OrderConfirmed(UUID eventId, UUID orderId, String customerId, Instant occurredAt) implements DomainEvent {

    public static OrderConfirmed of(UUID orderId, String customerId) {
        return new OrderConfirmed(UUID.randomUUID(), orderId, customerId, Instant.now());
    }
}
