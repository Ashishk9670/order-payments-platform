package com.example.shop.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.DomainEvent;
import com.example.shop.common.events.InventoryExpired;
import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCancelled;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.order.api.OrderChanged;
import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.domain.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class OrderSagaListenerTest {

    @Mock IdempotencyGuard guard;
    @Mock OrderRepository orders;
    @Mock OutboxService outbox;
    @Mock ApplicationEventPublisher spring;

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    OrderSagaListener listener;
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    Order order;

    @BeforeEach
    void setUp() {
        listener = new OrderSagaListener(mapper, guard, orders, outbox, spring, meters);
        order = Order.create("cust-1", List.of(new Order.ItemSpec("SKU-1", 1, new BigDecimal("5.00"))));
        lenient(order);
    }

    private void lenient(Order o) {
        org.mockito.Mockito.lenient().when(orders.findById(o.getId())).thenReturn(Optional.of(o));
        org.mockito.Mockito.lenient().when(guard.firstTime(any())).thenReturn(true);
    }

    private String json(DomainEvent e) throws Exception {
        return mapper.writeValueAsString(e);
    }

    @Test
    void inventoryReservedMovesOrderForward() throws Exception {
        listener.onInventoryReserved(json(InventoryReserved.of(order.getId(), "cust-1", order.getTotalAmount())));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(spring).publishEvent(new OrderChanged(order.getId()));
    }

    @Test
    void everyStatusTransitionIsCountedByTargetStatus() throws Exception {
        order.markInventoryReserved();
        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));
        // a duplicate that changes nothing must not be counted
        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));

        assertThat(meters.counter("orders.transitions", "status", "CONFIRMED").count()).isEqualTo(1.0);
    }

    @Test
    void paymentAuthorizedConfirmsOrderAndEmitsOrderConfirmed() throws Exception {
        order.markInventoryReserved();

        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outbox).publish(eq(Topics.ORDER_CONFIRMED), any(OrderConfirmed.class));
    }

    @Test
    void inventoryFailureCancelsOrderAndEmitsOrderCancelled() throws Exception {
        listener.onInventoryFailed(json(InventoryFailed.of(order.getId(), "out of stock")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).contains("out of stock");
        verify(outbox).publish(eq(Topics.ORDER_CANCELLED), any(OrderCancelled.class));
    }

    @Test
    void paymentFailureCancelsOrder() throws Exception {
        order.markInventoryReserved();

        listener.onPaymentFailed(json(PaymentFailed.of(order.getId(), "declined")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("Payment: declined");
        verify(outbox).publish(eq(Topics.ORDER_CANCELLED), any(OrderCancelled.class));
    }

    @Test
    void duplicateEventIsSkippedEntirely() throws Exception {
        var event = InventoryFailed.of(order.getId(), "x");
        when(guard.firstTime(event.eventId())).thenReturn(false);

        listener.onInventoryFailed(json(event));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void lateFailureAfterConfirmationDoesNotCancelOrEmit() throws Exception {
        order.markInventoryReserved();
        order.confirm();

        listener.onPaymentFailed(json(PaymentFailed.of(order.getId(), "late")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void eventForUnknownOrderIsIgnored() throws Exception {
        var unknown = UUID.randomUUID();
        when(orders.findById(unknown)).thenReturn(Optional.empty());

        listener.onInventoryFailed(json(InventoryFailed.of(unknown, "x")));

        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void paymentAuthorizedArrivingBeforeInventoryReservedStillConfirmsAndTheLateEventIsHarmless() throws Exception {
        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outbox).publish(eq(Topics.ORDER_CONFIRMED), any(OrderConfirmed.class));

        listener.onInventoryReserved(json(InventoryReserved.of(order.getId(), "cust-1", order.getTotalAmount())));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void refundCompletesTheCancellationAndEmitsOrderCancelled() throws Exception {
        order.markInventoryReserved();
        order.confirm();
        order.requestCancellation("Cancelled by customer");

        listener.onPaymentRefunded(json(PaymentRefunded.of(order.getId(), UUID.randomUUID())));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        var captor = org.mockito.ArgumentCaptor.forClass(OrderCancelled.class);
        verify(outbox).publish(eq(Topics.ORDER_CANCELLED), captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("Cancelled by customer");
    }

    @Test
    void refundForAnOrderNotAwaitingOneIsIgnored() throws Exception {
        order.markInventoryReserved();
        order.confirm();

        listener.onPaymentRefunded(json(PaymentRefunded.of(order.getId(), UUID.randomUUID())));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void refusedRefundKeepsTheOrderConfirmed() throws Exception {
        order.markInventoryReserved();
        order.confirm();
        order.requestCancellation("x");

        listener.onPaymentRefundFailed(json(PaymentRefundFailed.of(order.getId(), "declined")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(spring).publishEvent(new OrderChanged(order.getId()));
        verify(outbox, never()).publish(any(), any());
    }

    // ---- reservation expiry and the payment that arrives too late -----------------------------------------------------

    @Test
    void anExpiredReservationCancelsThePendingOrderAndTellsTheCustomer() throws Exception {
        order.markInventoryReserved();

        listener.onInventoryExpired(json(InventoryExpired.of(order.getId(), "Payment not received within PT30M")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).contains("Payment not received within PT30M");
        verify(outbox).publish(eq(Topics.ORDER_CANCELLED), any(OrderCancelled.class));
    }

    @Test
    void anExpiryEventForAnOrderThatWasAlreadyConfirmedChangesNothing() throws Exception {
        order.markInventoryReserved();
        order.confirm();

        listener.onInventoryExpired(json(InventoryExpired.of(order.getId(), "late")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void aPaymentThatArrivesAfterTheOrderWasCancelledIsRefundedNotKept() throws Exception {
        order.cancel("Inventory: Payment not received within PT30M");

        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);   // stays cancelled...
        var request = org.mockito.ArgumentCaptor.forClass(OrderCancellationRequested.class);
        verify(outbox).publish(eq(Topics.ORDER_CANCELLATION_REQUESTED), request.capture());   // ...and the refund path starts
        assertThat(request.getValue().orderId()).isEqualTo(order.getId());
        assertThat(request.getValue().reason()).contains("after the order was cancelled");
        assertThat(meters.counter("orders.late_payments_refunded").count()).isEqualTo(1.0);
        verify(outbox, never()).publish(eq(Topics.ORDER_CONFIRMED), any());
    }

    @Test
    void theLateRefundIsNotTriggeredInTheNormalPathOrWhileARefundIsAlreadyRunning() throws Exception {
        // normal: payment confirms a pending order
        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));
        verify(outbox, never()).publish(eq(Topics.ORDER_CANCELLATION_REQUESTED), any());

        // a customer refund is in flight: a duplicate-looking payment event must not start a second refund
        order.requestCancellation("Cancelled by customer");
        listener.onPaymentAuthorized(json(PaymentAuthorized.of(order.getId(), UUID.randomUUID())));
        verify(outbox, never()).publish(eq(Topics.ORDER_CANCELLATION_REQUESTED), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_PENDING);
    }

    @Test
    void aRedeliveredLatePaymentStartsOnlyOneRefund() throws Exception {
        order.cancel("expired");
        var late = PaymentAuthorized.of(order.getId(), UUID.randomUUID());
        when(guard.firstTime(late.eventId())).thenReturn(true, false);

        listener.onPaymentAuthorized(json(late));
        listener.onPaymentAuthorized(json(late));

        verify(outbox, org.mockito.Mockito.times(1)).publish(eq(Topics.ORDER_CANCELLATION_REQUESTED), any());
    }

    @Test
    void malformedPayloadThrowsNonRetryable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onInventoryFailed("garbage"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
