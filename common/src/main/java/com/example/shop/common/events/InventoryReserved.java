package com.example.shop.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryReserved(UUID eventId, UUID orderId, String customerId, BigDecimal totalAmount,
                                Instant occurredAt) implements DomainEvent {

    public static InventoryReserved of(UUID orderId, String customerId, BigDecimal totalAmount) {
        return new InventoryReserved(UUID.randomUUID(), orderId, customerId, totalAmount, Instant.now());
    }
}
