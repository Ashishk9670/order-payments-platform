package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record PaymentAuthorized(UUID eventId, UUID orderId, UUID paymentId, Instant occurredAt) implements DomainEvent {

    public static PaymentAuthorized of(UUID orderId, UUID paymentId) {
        return new PaymentAuthorized(UUID.randomUUID(), orderId, paymentId, Instant.now());
    }
}
