package com.example.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.payment.domain.Payment;
import com.example.shop.payment.domain.PaymentGateway;
import com.example.shop.payment.domain.PaymentProcessor;
import com.example.shop.payment.domain.PaymentRepository;
import com.example.shop.payment.gateway.SimulatedPaymentGateway;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentProcessorTest {

    @Mock PaymentGateway gateway;
    @Mock PaymentRepository payments;
    @Mock IdempotencyGuard guard;
    @Mock OutboxService outbox;

    private InventoryReserved event() {
        return InventoryReserved.of(UUID.randomUUID(), "cust-1", new BigDecimal("42.00"));
    }

    @Test
    void approvedPaymentIsSavedAndAuthorizedEventEmitted() {
        var event = event();
        when(guard.firstTime(any())).thenReturn(true);
        when(gateway.authorize(event.orderId(), event.totalAmount())).thenReturn(PaymentGateway.Result.approve());
        when(payments.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        new PaymentProcessor(gateway, payments, guard, outbox).process(event);

        var saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Payment.Status.AUTHORIZED);
        verify(outbox).publish(eq(Topics.PAYMENT_AUTHORIZED), any(PaymentAuthorized.class));
    }

    @Test
    void declinedPaymentIsRecordedAndFailedEventEmitted() {
        var event = event();
        when(guard.firstTime(any())).thenReturn(true);
        when(gateway.authorize(any(), any())).thenReturn(PaymentGateway.Result.decline("Insufficient funds"));

        new PaymentProcessor(gateway, payments, guard, outbox).process(event);

        var saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(saved.getValue().getFailureReason()).isEqualTo("Insufficient funds");
        var failed = ArgumentCaptor.forClass(PaymentFailed.class);
        verify(outbox).publish(eq(Topics.PAYMENT_FAILED), failed.capture());
        assertThat(failed.getValue().reason()).isEqualTo("Insufficient funds");
    }

    @Test
    void duplicateEventDoesNotChargeAgain() {
        when(guard.firstTime(any())).thenReturn(false);

        new PaymentProcessor(gateway, payments, guard, outbox).process(event());

        verify(gateway, never()).authorize(any(), any());
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void simulatedGatewayDeclinesOnlyAboveLimit() {
        var gw = new SimulatedPaymentGateway(new BigDecimal("100"));
        assertThat(gw.authorize(UUID.randomUUID(), new BigDecimal("100")).approved()).isTrue();
        var declined = gw.authorize(UUID.randomUUID(), new BigDecimal("100.01"));
        assertThat(declined.approved()).isFalse();
        assertThat(declined.declineReason()).isNotBlank();
    }

    private Payment authorizedPayment(UUID orderId) {
        var payment = Payment.authorized(orderId, new BigDecimal("42.00"));
        when(payments.findByOrderId(orderId)).thenReturn(java.util.Optional.of(payment));
        return payment;
    }

    @Test
    void refundOfAuthorizedPaymentMarksItRefundedAndEmitsEvent() {
        var orderId = UUID.randomUUID();
        var payment = authorizedPayment(orderId);
        when(guard.firstTime(any())).thenReturn(true);
        when(gateway.refund(orderId, payment.getAmount())).thenReturn(PaymentGateway.Result.approve());

        new PaymentProcessor(gateway, payments, guard, outbox).refund(OrderCancellationRequested.of(orderId, "x"));

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        verify(outbox).publish(eq(Topics.PAYMENT_REFUNDED), any(PaymentRefunded.class));
    }

    @Test
    void refundIsRefusedWhenTheGatewayDeclines() {
        var orderId = UUID.randomUUID();
        var payment = authorizedPayment(orderId);
        when(guard.firstTime(any())).thenReturn(true);
        when(gateway.refund(any(), any())).thenReturn(PaymentGateway.Result.decline("PSP error"));

        new PaymentProcessor(gateway, payments, guard, outbox).refund(OrderCancellationRequested.of(orderId, "x"));

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.AUTHORIZED);
        var failed = ArgumentCaptor.forClass(PaymentRefundFailed.class);
        verify(outbox).publish(eq(Topics.PAYMENT_REFUND_FAILED), failed.capture());
        assertThat(failed.getValue().reason()).isEqualTo("PSP error");
    }

    @Test
    void refundWithoutAnAuthorizedPaymentFailsWithoutCallingTheGateway() {
        var orderId = UUID.randomUUID();
        when(guard.firstTime(any())).thenReturn(true);
        when(payments.findByOrderId(orderId)).thenReturn(java.util.Optional.empty());

        new PaymentProcessor(gateway, payments, guard, outbox).refund(OrderCancellationRequested.of(orderId, "x"));

        verify(gateway, never()).refund(any(), any());
        verify(outbox).publish(eq(Topics.PAYMENT_REFUND_FAILED), any(PaymentRefundFailed.class));
    }

    @Test
    void anAlreadyRefundedPaymentCannotBeRefundedTwice() {
        var orderId = UUID.randomUUID();
        var payment = authorizedPayment(orderId);
        payment.refund();
        when(guard.firstTime(any())).thenReturn(true);

        new PaymentProcessor(gateway, payments, guard, outbox).refund(OrderCancellationRequested.of(orderId, "x"));

        verify(gateway, never()).refund(any(), any());
        verify(outbox).publish(eq(Topics.PAYMENT_REFUND_FAILED), any(PaymentRefundFailed.class));
    }

    @Test
    void duplicateCancellationEventIsIgnored() {
        when(guard.firstTime(any())).thenReturn(false);

        new PaymentProcessor(gateway, payments, guard, outbox).refund(OrderCancellationRequested.of(UUID.randomUUID(), "x"));

        verify(gateway, never()).refund(any(), any());
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void simulatedGatewayAlwaysApprovesRefunds() {
        assertThat(new SimulatedPaymentGateway(new BigDecimal("1")).refund(UUID.randomUUID(), new BigDecimal("999")).approved()).isTrue();
    }
}
