package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

/** Stock was reserved for an order but payment never arrived in time, so the reservation was released. */
public record InventoryExpired(UUID eventId, UUID orderId, String reason, Instant occurredAt) implements DomainEvent {

    public static InventoryExpired of(UUID orderId, String reason) {
        return new InventoryExpired(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
