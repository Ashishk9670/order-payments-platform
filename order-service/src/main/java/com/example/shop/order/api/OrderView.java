package com.example.shop.order.api;

import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderStatus;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model returned by REST. Serializable because it is cached in Redis. */
public record OrderView(UUID id, String customerId, OrderStatus status, BigDecimal totalAmount, String cancelReason,
                        Instant createdAt, List<ItemView> items) implements Serializable {

    public record ItemView(String sku, int quantity, BigDecimal unitPrice) implements Serializable {
    }

    public static OrderView from(Order o) {
        return new OrderView(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalAmount(), o.getCancelReason(),
                o.getCreatedAt(),
                o.getItems().stream().map(i -> new ItemView(i.getSku(), i.getQuantity(), i.getUnitPrice())).toList());
    }
}
