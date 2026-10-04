package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record PaymentRefunded(UUID eventId, UUID orderId, UUID paymentId, Instant occurredAt) implements DomainEvent {

    public static PaymentRefunded of(UUID orderId, UUID paymentId) {
        return new PaymentRefunded(UUID.randomUUID(), orderId, paymentId, Instant.now());
    }
}
