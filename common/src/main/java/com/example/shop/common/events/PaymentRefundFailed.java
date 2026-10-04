package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record PaymentRefundFailed(UUID eventId, UUID orderId, String reason, Instant occurredAt) implements DomainEvent {

    public static PaymentRefundFailed of(UUID orderId, String reason) {
        return new PaymentRefundFailed(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
