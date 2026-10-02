package com.example.shop.order.graphql;

import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Read side for storefront/UI clients. Order rows are cheap; items are resolved with a @BatchMapping, so a query
 * for 20 orders with items costs 2 SQL statements, not 21.
 */
@Controller
class OrderGraphQlController {

    record OrderSummary(UUID id, String customerId, OrderStatus status, BigDecimal totalAmount, String cancelReason,
                        Instant createdAt) {
        static OrderSummary from(Order o) {
            return new OrderSummary(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalAmount(), o.getCancelReason(),
                    o.getCreatedAt());
        }
    }

    record Item(String sku, int quantity, BigDecimal unitPrice) {
    }

    private final OrderRepository orders;

    OrderGraphQlController(OrderRepository orders) {
        this.orders = orders;
    }

    @QueryMapping
    OrderSummary order(@Argument UUID id) {
        return orders.findById(id).map(OrderSummary::from).orElse(null);
    }

    @QueryMapping
    List<OrderSummary> ordersByCustomer(@Argument String customerId, @Argument int page, @Argument int size) {
        return orders.findByCustomerIdOrderByCreatedAtDesc(customerId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))).stream().map(OrderSummary::from).toList();
    }

    @BatchMapping(typeName = "Order", field = "items")
    Map<OrderSummary, List<Item>> items(List<OrderSummary> parents) {
        Map<UUID, List<Item>> byOrder = orders.findItemRows(parents.stream().map(OrderSummary::id).toList()).stream()
                .collect(Collectors.groupingBy(OrderRepository.ItemRow::orderId,
                        Collectors.mapping(r -> new Item(r.sku(), r.quantity(), r.unitPrice()), Collectors.toList())));
        return parents.stream().collect(Collectors.toMap(p -> p, p -> byOrder.getOrDefault(p.id(), List.of())));
    }
}
