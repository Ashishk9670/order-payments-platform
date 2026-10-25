package com.example.shop.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate root. State transitions are guarded so duplicate or late saga events cannot corrupt the state. */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private String customerId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;
    @Column(name = "cancel_reason")
    private String cancelReason;
    @Enumerated(EnumType.STRING)
    private Fulfillment fulfillment;
    private String carrier;
    @Column(name = "tracking_number")
    private String trackingNumber;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order create(String customerId, List<ItemSpec> specs) {
        Order o = new Order();
        o.id = UUID.randomUUID();
        o.customerId = customerId;
        o.status = OrderStatus.PENDING;
        o.createdAt = o.updatedAt = Instant.now();
        BigDecimal total = BigDecimal.ZERO;
        for (ItemSpec s : specs) {
            o.items.add(new OrderItem(o, s.sku(), s.quantity(), s.unitPrice()));
            total = total.add(s.unitPrice().multiply(BigDecimal.valueOf(s.quantity())));
        }
        o.totalAmount = total;
        return o;
    }

    public record ItemSpec(String sku, int quantity, BigDecimal unitPrice) {
    }

    /** @return true if the state changed (false = duplicate / out-of-order event, ignore). */
    public boolean markInventoryReserved() {
        if (status != OrderStatus.PENDING) {
            return false;
        }
        transition(OrderStatus.INVENTORY_RESERVED);
        return true;
    }

    /**
     * Payment is only ever requested after stock was reserved, so PaymentAuthorized proves the reservation. The two
     * events travel on different topics with no ordering guarantee between them, so it may arrive BEFORE
     * InventoryReserved; accepting it from PENDING avoids an order that is paid but stuck. The late InventoryReserved
     * is then a harmless no-op (markInventoryReserved only moves a PENDING order).
     */
    public boolean confirm() {
        if (status != OrderStatus.PENDING && status != OrderStatus.INVENTORY_RESERVED) {
            return false;
        }
        transition(OrderStatus.CONFIRMED);
        return true;
    }

    public boolean cancel(String reason) {
        if (status == OrderStatus.CONFIRMED || status == OrderStatus.CANCELLED || status == OrderStatus.REFUND_PENDING) {
            return false;
        }
        this.cancelReason = reason;
        transition(OrderStatus.CANCELLED);
        return true;
    }

    /** Customer-initiated cancellation: only a confirmed order that has not left the warehouse can be refunded. */
    public boolean requestCancellation(String reason) {
        if (status != OrderStatus.CONFIRMED || fulfillment != null) {
            return false;
        }
        this.cancelReason = reason;
        transition(OrderStatus.REFUND_PENDING);
        return true;
    }

    public boolean completeCancellation() {
        if (status != OrderStatus.REFUND_PENDING) {
            return false;
        }
        transition(OrderStatus.CANCELLED);
        return true;
    }

    /** The refund was refused, so the order stays valid. */
    public boolean revertCancellation() {
        if (status != OrderStatus.REFUND_PENDING) {
            return false;
        }
        this.cancelReason = null;
        transition(OrderStatus.CONFIRMED);
        return true;
    }

    /** The parcel left the warehouse. Only a confirmed order ships; a repeat or late event changes nothing. */
    public boolean markShipped(String carrier, String trackingNumber) {
        if (status != OrderStatus.CONFIRMED || fulfillment != null) {
            return false;
        }
        this.fulfillment = Fulfillment.SHIPPED;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean markDelivered() {
        if (fulfillment != Fulfillment.SHIPPED) {
            return false;
        }
        this.fulfillment = Fulfillment.DELIVERED;
        this.updatedAt = Instant.now();
        return true;
    }

    private void transition(OrderStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Fulfillment getFulfillment() {
        return fulfillment;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
