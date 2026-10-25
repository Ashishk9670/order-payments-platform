package com.example.shop.common.events;

import java.time.Instant;
import java.util.UUID;

/** The parcel for a confirmed order has left the warehouse. From here on the order can no longer be cancelled. */
public record OrderShipped(UUID eventId, UUID orderId, String carrier, String trackingNumber, Instant occurredAt)
        implements DomainEvent {

    public static OrderShipped of(UUID orderId, String carrier, String trackingNumber) {
        return new OrderShipped(UUID.randomUUID(), orderId, carrier, trackingNumber, Instant.now());
    }
}
