package com.example.shop.payment.domain;

import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final IdempotencyGuard guard;
    private final OutboxService outbox;

    public PaymentProcessor(PaymentGateway gateway, PaymentRepository payments, IdempotencyGuard guard, OutboxService outbox) {
        this.gateway = gateway;
        this.payments = payments;
        this.guard = guard;
        this.outbox = outbox;
    }

    @Transactional
    public void process(InventoryReserved event) {
        if (!guard.firstTime(event.eventId())) {
            return;
        }
        var result = gateway.authorize(event.orderId(), event.totalAmount());
        if (result.approved()) {
            Payment payment = payments.save(Payment.authorized(event.orderId(), event.totalAmount()));
            outbox.publish(Topics.PAYMENT_AUTHORIZED, PaymentAuthorized.of(event.orderId(), payment.getId()));
        } else {
            payments.save(Payment.failed(event.orderId(), event.totalAmount(), result.declineReason()));
            outbox.publish(Topics.PAYMENT_FAILED, PaymentFailed.of(event.orderId(), result.declineReason()));
        }
        log.info("Payment for order {}: {}", event.orderId(), result.approved() ? "AUTHORIZED" : "FAILED");
    }

    /** Refund saga: money goes back only for an authorized payment, and only once. */
    @Transactional
    public void refund(OrderCancellationRequested event) {
        if (!guard.firstTime(event.eventId())) {
            return;
        }
        var payment = payments.findByOrderId(event.orderId()).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.AUTHORIZED) {
            outbox.publish(Topics.PAYMENT_REFUND_FAILED,
                    PaymentRefundFailed.of(event.orderId(), "No authorized payment to refund"));
            log.info("Refund for order {} rejected: nothing to refund", event.orderId());
            return;
        }
        var result = gateway.refund(event.orderId(), payment.getAmount());
        if (result.approved() && payment.refund()) {
            outbox.publish(Topics.PAYMENT_REFUNDED, PaymentRefunded.of(event.orderId(), payment.getId()));
        } else {
            outbox.publish(Topics.PAYMENT_REFUND_FAILED, PaymentRefundFailed.of(event.orderId(),
                    result.declineReason() == null ? "Refund declined" : result.declineReason()));
        }
        log.info("Refund for order {}: {}", event.orderId(), result.approved() ? "REFUNDED" : "FAILED");
    }
}
