package com.example.shop.order.graphql;

import com.example.shop.order.api.OrderNotFoundException;
import com.example.shop.order.api.OrderPlacementService;
import com.example.shop.order.api.OrderService;
import com.example.shop.order.api.PlaceOrderRequest;
import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.Fulfillment;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.domain.OrderStatus;
import com.example.shop.order.security.CurrentUser;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * GraphQL API: queries for the storefront read model, mutations that reuse the same services as REST, and an
 * orderStatusChanged subscription (SSE) that replaces client polling.
 */
@Controller
@Validated
class OrderGraphQlController {

    record OrderSummary(UUID id, String customerId, OrderStatus status, BigDecimal totalAmount, String cancelReason,
                        Instant createdAt, Fulfillment fulfillment, String carrier, String trackingNumber) {
        static OrderSummary from(Order o) {
            return new OrderSummary(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalAmount(), o.getCancelReason(),
                    o.getCreatedAt(), o.getFulfillment(), o.getCarrier(), o.getTrackingNumber());
        }
    }

    record Item(String sku, int quantity, BigDecimal unitPrice) {
    }

    record StatusUpdate(UUID id, OrderStatus status, String cancelReason) {
    }

    private final OrderRepository orders;
    private final OrderPlacementService placement;
    private final OrderService orderService;
    private final OrderUpdates updates;
    private final CurrentUser access;

    OrderGraphQlController(OrderRepository orders, OrderPlacementService placement, OrderService orderService,
                           OrderUpdates updates, CurrentUser access) {
        this.orders = orders;
        this.placement = placement;
        this.orderService = orderService;
        this.updates = updates;
        this.access = access;
    }

    @QueryMapping
    OrderSummary order(@Argument UUID id) {
        return orders.findById(id).filter(o -> access.canAccess(o.getCustomerId())).map(OrderSummary::from).orElse(null);
    }

    @QueryMapping
    List<OrderSummary> ordersByCustomer(@Argument String customerId, @Argument OrderStatus status, @Argument int page,
                                        @Argument int size) {
        access.requireAccess(customerId);
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var found = status == null
                ? orders.findByCustomerIdOrderByCreatedAtDesc(customerId, paging)
                : orders.findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, status, paging);
        return found.stream().map(OrderSummary::from).toList();
    }

    @QueryMapping
    int orderCountByCustomer(@Argument String customerId, @Argument OrderStatus status) {
        access.requireAccess(customerId);
        return (int) (status == null ? orders.countByCustomerId(customerId) : orders.countByCustomerIdAndStatus(customerId, status));
    }

    @MutationMapping
    OrderSummary placeOrder(@Argument("input") @Valid PlaceOrderRequest input) {
        access.requireAccess(input.customerId());
        var view = placement.place(input);
        return orders.findById(view.id()).map(OrderSummary::from).orElseThrow(() -> new OrderNotFoundException(view.id()));
    }

    @MutationMapping
    OrderSummary cancelOrder(@Argument UUID id, @Argument String reason) {
        orders.findById(id).filter(o -> access.canAccess(o.getCustomerId())).orElseThrow(() -> new OrderNotFoundException(id));
        orderService.requestCancellation(id, reason);
        return orders.findById(id).map(OrderSummary::from).orElseThrow(() -> new OrderNotFoundException(id));
    }

    /**
     * Emits the current status immediately, then every change, and completes at a terminal state (or after 5 min).
     * The hot stream is subscribed together with the initial load, so a change between "load" and "subscribe" is not lost.
     */
    @SubscriptionMapping
    Flux<StatusUpdate> orderStatusChanged(@Argument UUID id) {
        // authorize once, up front, on the request thread (the stream itself runs on other threads)
        orders.findById(id).filter(o -> access.canAccess(o.getCustomerId())).orElseThrow(() -> new OrderNotFoundException(id));
        return Flux.merge(Flux.just(id), updates.changesOf(id))
                .concatMap(oid -> Mono.fromCallable(() -> orders.findById(oid)
                                .map(o -> new StatusUpdate(o.getId(), o.getStatus(), o.getCancelReason()))
                                .orElseThrow(() -> new OrderNotFoundException(oid)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .distinctUntilChanged(StatusUpdate::status)
                .takeUntil(u -> u.status().isTerminal())
                .take(Duration.ofMinutes(5));
    }

    @BatchMapping(typeName = "Order", field = "items")
    Map<OrderSummary, List<Item>> items(List<OrderSummary> parents) {
        Map<UUID, List<Item>> byOrder = orders.findItemRows(parents.stream().map(OrderSummary::id).toList()).stream()
                .collect(Collectors.groupingBy(OrderRepository.ItemRow::orderId,
                        Collectors.mapping(r -> new Item(r.sku(), r.quantity(), r.unitPrice()), Collectors.toList())));
        return parents.stream().collect(Collectors.toMap(p -> p, p -> byOrder.getOrDefault(p.id(), List.of())));
    }
}
