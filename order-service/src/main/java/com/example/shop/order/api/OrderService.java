package com.example.shop.order.api;

import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.Topics;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, OutboxService outbox, ApplicationEventPublisher events) {
        this.orders = orders;
        this.outbox = outbox;
        this.events = events;
    }

    /** Saves the order and its OrderCreated event in one transaction (outbox pattern). Prices are already resolved. */
    @Transactional
    public OrderView create(String customerId, List<Order.ItemSpec> lines) {
        Order order = Order.create(customerId, lines);
        orders.save(order);
        outbox.publish(Topics.ORDER_CREATED, OrderCreated.of(order.getId(), customerId,
                lines.stream().map(l -> new OrderCreated.Line(l.sku(), l.quantity())).toList(), order.getTotalAmount()));
        return OrderView.from(order);
    }

    /** Starts the refund saga for a confirmed order. */
    @Transactional
    public OrderView requestCancellation(UUID id, String reason) {
        Order order = orders.findWithItemsById(id).orElseThrow(() -> new OrderNotFoundException(id));
        String text = reason == null || reason.isBlank() ? "Cancelled by customer" : "Cancelled by customer: " + reason.trim();
        if (!order.requestCancellation(text)) {
            throw new OrderStateConflictException(
                    "Only confirmed orders can be cancelled; order is " + order.getStatus());
        }
        outbox.publish(Topics.ORDER_CANCELLATION_REQUESTED, OrderCancellationRequested.of(id, text));
        events.publishEvent(new OrderChanged(id));
        return OrderView.from(order);
    }

    @Cacheable(cacheNames = "orders", key = "#id")
    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        return orders.findWithItemsById(id).map(OrderView::from).orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** Two queries total regardless of page size: the page of orders, then all their items at once. */
    @Transactional(readOnly = true)
    public List<OrderView> listByCustomer(String customerId, int page, int size) {
        List<Order> page0 = orders.findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(page, size));
        Map<UUID, List<OrderView.ItemView>> items = orders.findItemRows(page0.stream().map(Order::getId).toList())
                .stream().collect(Collectors.groupingBy(OrderRepository.ItemRow::orderId,
                        Collectors.mapping(r -> new OrderView.ItemView(r.sku(), r.quantity(), r.unitPrice()),
                                Collectors.toList())));
        return page0.stream().map(o -> new OrderView(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalAmount(),
                o.getCancelReason(), o.getCreatedAt(), items.getOrDefault(o.getId(), List.of()))).toList();
    }
}
