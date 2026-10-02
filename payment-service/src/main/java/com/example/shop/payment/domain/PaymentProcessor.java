package com.example.shop.payment.domain;

import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
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
}
