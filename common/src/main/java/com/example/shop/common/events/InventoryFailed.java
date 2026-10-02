package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

public record InventoryFailed(UUID eventId, UUID orderId, String reason, Instant occurredAt) implements DomainEvent {

    public static InventoryFailed of(UUID orderId, String reason) {
        return new InventoryFailed(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
