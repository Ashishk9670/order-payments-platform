package com.example.shop.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment")
public class Payment {

    public enum Status { AUTHORIZED, FAILED, REFUNDED }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;
    @Column(nullable = false)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "failure_reason")
    private String failureReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public static Payment authorized(UUID orderId, BigDecimal amount) {
        return of(orderId, amount, Status.AUTHORIZED, null);
    }

    public static Payment failed(UUID orderId, BigDecimal amount, String reason) {
        return of(orderId, amount, Status.FAILED, reason);
    }

    private static Payment of(UUID orderId, BigDecimal amount, Status status, String reason) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.orderId = orderId;
        p.amount = amount;
        p.status = status;
        p.failureReason = reason;
        p.createdAt = Instant.now();
        return p;
    }

    /** @return false unless the payment is currently authorized (a payment can be refunded only once). */
    public boolean refund() {
        if (status != Status.AUTHORIZED) {
            return false;
        }
        status = Status.REFUNDED;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
