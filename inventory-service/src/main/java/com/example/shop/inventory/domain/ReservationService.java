package com.example.shop.inventory.domain;

import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final InventoryItemRepository items;
    private final ReservationRepository reservations;
    private final IdempotencyGuard guard;
    private final OutboxService outbox;

    public ReservationService(InventoryItemRepository items, ReservationRepository reservations,
                              IdempotencyGuard guard, OutboxService outbox) {
        this.items = items;
        this.reservations = reservations;
        this.guard = guard;
        this.outbox = outbox;
    }

    /** All-or-nothing: if any line is short, lines already taken for this order are given back. */
    @Transactional
    public void reserve(OrderCreated order) {
        if (!guard.firstTime(order.eventId())) {
            return;
        }
        List<OrderCreated.Line> lines = order.items().stream().sorted(Comparator.comparing(OrderCreated.Line::sku)).toList();
        List<OrderCreated.Line> taken = new ArrayList<>();
        for (OrderCreated.Line line : lines) {
            if (items.tryReserve(line.sku(), line.quantity()) == 0) {
                taken.forEach(t -> items.release(t.sku(), t.quantity()));
                String reason = "Insufficient stock or unknown SKU: " + line.sku();
                outbox.publish(Topics.INVENTORY_FAILED, InventoryFailed.of(order.orderId(), reason));
                log.info("Order {} rejected: {}", order.orderId(), reason);
                return;
            }
            taken.add(line);
        }
        lines.forEach(l -> reservations.save(new Reservation(order.orderId(), l.sku(), l.quantity())));
        outbox.publish(Topics.INVENTORY_RESERVED,
                InventoryReserved.of(order.orderId(), order.customerId(), order.totalAmount()));
        log.info("Order {} reserved ({} lines)", order.orderId(), lines.size());
    }

    /** Compensation step of the order saga. */
    @Transactional
    public void releaseFor(PaymentFailed event) {
        release(event.eventId(), event.orderId(), "payment failure");
    }

    /** Last step of the refund saga: the sale is undone, so the stock goes back on the shelf. */
    @Transactional
    public void releaseFor(PaymentRefunded event) {
        release(event.eventId(), event.orderId(), "refund");
    }

    private void release(UUID eventId, UUID orderId, String why) {
        if (!guard.firstTime(eventId)) {
            return;
        }
        for (Reservation r : reservations.findByOrderIdAndStatus(orderId, Reservation.Status.RESERVED)) {
            items.release(r.getSku(), r.getQuantity());
            r.release();
        }
        log.info("Order {} stock released after {}", orderId, why);
    }
}
