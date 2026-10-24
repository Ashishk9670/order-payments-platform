package com.example.shop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** What was reserved for an order, so it can be released if payment fails. */
@Entity
@Table(name = "reservation")
public class Reservation {

    /**
     * RESERVED: held, waiting for payment. COMMITTED: paid, the stock is sold. RELEASED: given back (payment failed or refund).
     * EXPIRED: payment never arrived in time, given back by the expiry job.
     */
    public enum Status { RESERVED, COMMITTED, RELEASED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(nullable = false)
    private String sku;
    @Column(nullable = false)
    private int quantity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reservation() {
    }

    public Reservation(UUID orderId, String sku, int quantity) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now();
    }

    public void release() {
        this.status = Status.RELEASED;
    }

    /** Payment arrived: the stock is sold and must never be expired. */
    public void commit() {
        this.status = Status.COMMITTED;
    }

    public void expire() {
        this.status = Status.EXPIRED;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public Status getStatus() {
        return status;
    }
}
