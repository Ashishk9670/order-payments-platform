package com.example.shop.order.saga;

import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.JsonEvents;
import com.example.shop.common.events.OrderCancelled;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.order.domain.Order;
import com.example.shop.order.api.OrderChanged;
import com.example.shop.order.domain.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order's side of the choreography saga. Happy path: OrderCreated -> InventoryReserved -> PaymentAuthorized ->
 * OrderConfirmed. Failure paths cancel the order (and inventory releases stock itself when PaymentFailed arrives).
 */
@Component
public class OrderSagaListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaListener.class);
    private static final String GROUP = "order-service";

    private final ObjectMapper mapper;
    private final IdempotencyGuard guard;
    private final OrderRepository orders;
    private final OutboxService outbox;
    private final ApplicationEventPublisher spring;

    public OrderSagaListener(ObjectMapper mapper, IdempotencyGuard guard, OrderRepository orders,
                             OutboxService outbox, ApplicationEventPublisher spring) {
        this.mapper = mapper;
        this.guard = guard;
        this.orders = orders;
        this.outbox = outbox;
        this.spring = spring;
    }

    @KafkaListener(topics = Topics.INVENTORY_RESERVED, groupId = GROUP)
    @Transactional
    public void onInventoryReserved(String json) {
        var e = JsonEvents.read(mapper, json, InventoryReserved.class);
        apply(e.eventId(), e.orderId(), Order::markInventoryReserved, null);
    }

    @KafkaListener(topics = Topics.INVENTORY_FAILED, groupId = GROUP)
    @Transactional
    public void onInventoryFailed(String json) {
        var e = JsonEvents.read(mapper, json, InventoryFailed.class);
        apply(e.eventId(), e.orderId(), o -> o.cancel("Inventory: " + e.reason()), cancelled(e.reason()));
    }

    @KafkaListener(topics = Topics.PAYMENT_AUTHORIZED, groupId = GROUP)
    @Transactional
    public void onPaymentAuthorized(String json) {
        var e = JsonEvents.read(mapper, json, PaymentAuthorized.class);
        apply(e.eventId(), e.orderId(), Order::confirm,
                o -> outbox.publish(Topics.ORDER_CONFIRMED, OrderConfirmed.of(o.getId(), o.getCustomerId())));
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED, groupId = GROUP)
    @Transactional
    public void onPaymentFailed(String json) {
        var e = JsonEvents.read(mapper, json, PaymentFailed.class);
        apply(e.eventId(), e.orderId(), o -> o.cancel("Payment: " + e.reason()), cancelled(e.reason()));
    }

    /** Refund saga, last step: money is back, so the order is finally cancelled. */
    @KafkaListener(topics = Topics.PAYMENT_REFUNDED, groupId = GROUP)
    @Transactional
    public void onPaymentRefunded(String json) {
        var e = JsonEvents.read(mapper, json, PaymentRefunded.class);
        apply(e.eventId(), e.orderId(), Order::completeCancellation,
                o -> outbox.publish(Topics.ORDER_CANCELLED,
                        OrderCancelled.of(o.getId(), o.getCustomerId(), o.getCancelReason())));
    }

    /** The refund was refused: the customer keeps a valid order. */
    @KafkaListener(topics = Topics.PAYMENT_REFUND_FAILED, groupId = GROUP)
    @Transactional
    public void onPaymentRefundFailed(String json) {
        var e = JsonEvents.read(mapper, json, PaymentRefundFailed.class);
        apply(e.eventId(), e.orderId(), Order::revertCancellation, null);
    }

    private java.util.function.Consumer<Order> cancelled(String reason) {
        return o -> outbox.publish(Topics.ORDER_CANCELLED, OrderCancelled.of(o.getId(), o.getCustomerId(), reason));
    }

    private void apply(UUID eventId, UUID orderId, Predicate<Order> transition, java.util.function.Consumer<Order> onChanged) {
        if (!guard.firstTime(eventId)) {
            log.debug("Duplicate event {} skipped", eventId);
            return;
        }
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            // not retryable: the order will not appear later; a DLT entry would only add noise
            log.warn("Event {} for unknown order {} ignored", eventId, orderId);
            return;
        }
        if (transition.test(order)) {
            if (onChanged != null) {
                onChanged.accept(order);
            }
            spring.publishEvent(new OrderChanged(orderId));
            log.info("Order {} -> {}", orderId, order.getStatus());
        } else {
            log.info("Order {} stays {}: event {} not applicable", orderId, order.getStatus(), eventId);
        }
    }
}
