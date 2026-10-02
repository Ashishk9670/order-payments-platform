package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record PaymentFailed(UUID eventId, UUID orderId, String reason, Instant occurredAt) implements DomainEvent {

    public static PaymentFailed of(UUID orderId, String reason) {
        return new PaymentFailed(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
