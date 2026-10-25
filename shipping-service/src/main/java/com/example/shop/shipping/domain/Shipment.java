package com.example.shop.shipping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One shipment per confirmed order. PENDING (waiting to be packed) -> SHIPPED -> DELIVERED, or PENDING -> CANCELLED when the
 * customer cancels first. Every transition returns false when it is not allowed from the current state, so redelivered events and
 * double clicks are harmless.
 */
@Entity
@Table(name = "shipment")
public class Shipment {

    public enum Status { PENDING, SHIPPED, DELIVERED, CANCELLED }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private String carrier;
    @Column(name = "tracking_number")
    private String trackingNumber;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "shipped_at")
    private Instant shippedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected Shipment() {
    }

    public static Shipment pending(UUID orderId, Instant now) {
        return create(orderId, Status.PENDING, now);
    }

    /** Recorded when a cancellation arrives before the order's confirmation, so the late confirmation cannot start a shipment. */
    public static Shipment cancelled(UUID orderId, Instant now) {
        return create(orderId, Status.CANCELLED, now);
    }

    private static Shipment create(UUID orderId, Status status, Instant now) {
        Shipment s = new Shipment();
        s.id = UUID.randomUUID();
        s.orderId = orderId;
        s.status = status;
        s.createdAt = now;
        return s;
    }

    public boolean ship(String carrier, String trackingNumber, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        this.status = Status.SHIPPED;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.shippedAt = now;
        return true;
    }

    public boolean deliver(Instant now) {
        if (status != Status.SHIPPED) {
            return false;
        }
        this.status = Status.DELIVERED;
        this.deliveredAt = now;
        return true;
    }

    public boolean cancel() {
        if (status != Status.PENDING) {
            return false;
        }
        this.status = Status.CANCELLED;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Status getStatus() {
        return status;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
