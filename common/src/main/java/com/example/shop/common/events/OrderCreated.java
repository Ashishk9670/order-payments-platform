package com.example.shop.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreated(UUID eventId, UUID orderId, String customerId, List<Line> items,
                           BigDecimal totalAmount, Instant occurredAt) implements DomainEvent {

    public record Line(String sku, int quantity) {
    }

    public static OrderCreated of(UUID orderId, String customerId, List<Line> items, BigDecimal total) {
        return new OrderCreated(UUID.randomUUID(), orderId, customerId, items, total, Instant.now());
    }
}
