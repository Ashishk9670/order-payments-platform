package com.example.shop.shipping.domain;

import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.OrderDelivered;
import com.example.shop.common.events.OrderShipped;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);

    /** What happened when an operator asked for a transition. */
    public enum Outcome { DONE, NOT_FOUND, NOT_ALLOWED }

    private final ShipmentRepository shipments;
    private final IdempotencyGuard guard;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final Clock clock;

    public ShippingService(ShipmentRepository shipments, IdempotencyGuard guard, OutboxService outbox, MeterRegistry meters,
                           Clock clock) {
        this.shipments = shipments;
        this.guard = guard;
        this.outbox = outbox;
        this.meters = meters;
        this.clock = clock;
    }

    /** A confirmed (paid, in-stock) order enters the warehouse queue. */
    @Transactional
    public void onOrderConfirmed(OrderConfirmed event) {
        if (!guard.firstTime(event.eventId())) {
            return;
        }
        // a cancellation that overtook this event already left a CANCELLED record: nothing to ship
        if (shipments.findByOrderId(event.orderId()).isEmpty()) {
            shipments.save(Shipment.pending(event.orderId(), clock.instant()));
            meters.counter("shipments.created").increment();
            log.info("Order {} is waiting to be shipped", event.orderId());
        }
    }

    /**
     * The customer asked to cancel. A parcel not yet sent is simply not sent. One already on its way cannot be recalled: that is
     * counted and logged for an operator (the order service stops offering cancellation once it hears about the shipment, so this
     * only happens in the short window before it does).
     */
    @Transactional
    public void onCancellationRequested(OrderCancellationRequested event) {
        if (!guard.firstTime(event.eventId())) {
            return;
        }
        var shipment = shipments.findByOrderId(event.orderId()).orElse(null);
        if (shipment == null) {
            shipments.save(Shipment.cancelled(event.orderId(), clock.instant()));
            return;
        }
        if (shipment.cancel()) {
            meters.counter("shipments.cancelled").increment();
            log.info("Shipment of order {} cancelled before dispatch", event.orderId());
        } else if (shipment.getStatus() != Shipment.Status.CANCELLED) {
            meters.counter("shipments.cancel_too_late").increment();
            log.warn("Order {} was cancelled but its shipment is already {}: needs manual follow-up", event.orderId(),
                    shipment.getStatus());
        }
    }

    @Transactional
    public Outcome ship(UUID orderId, String carrier, String trackingNumber) {
        var shipment = shipments.findByOrderId(orderId).orElse(null);
        if (shipment == null) {
            return Outcome.NOT_FOUND;
        }
        if (!shipment.ship(carrier, trackingNumber, clock.instant())) {
            return Outcome.NOT_ALLOWED;
        }
        outbox.publish(Topics.SHIPMENT_SHIPPED, OrderShipped.of(orderId, carrier, trackingNumber));
        meters.counter("shipments.shipped").increment();
        return Outcome.DONE;
    }

    @Transactional
    public Outcome deliver(UUID orderId) {
        var shipment = shipments.findByOrderId(orderId).orElse(null);
        if (shipment == null) {
            return Outcome.NOT_FOUND;
        }
        if (!shipment.deliver(clock.instant())) {
            return Outcome.NOT_ALLOWED;
        }
        outbox.publish(Topics.SHIPMENT_DELIVERED, OrderDelivered.of(orderId));
        meters.counter("shipments.delivered").increment();
        return Outcome.DONE;
    }
}
