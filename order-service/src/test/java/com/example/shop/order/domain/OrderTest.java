package com.example.shop.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

    private Order newOrder() {
        return Order.create("cust-1", List.of(
                new Order.ItemSpec("SKU-1", 2, new BigDecimal("10.50")),
                new Order.ItemSpec("SKU-2", 1, new BigDecimal("4.00"))));
    }

    @Test
    void totalIsSumOfLines() {
        var order = newOrder();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void happyPathTransitions() {
        var order = newOrder();
        assertThat(order.markInventoryReserved()).isTrue();
        assertThat(order.confirm()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void duplicateEventsAreNoOps() {
        var order = newOrder();
        order.markInventoryReserved();
        assertThat(order.markInventoryReserved()).isFalse();
        order.confirm();
        assertThat(order.confirm()).isFalse();
    }

    @Test
    void paymentAuthorizedBeforeInventoryReservedStillConfirms() {
        var order = newOrder();

        assertThat(order.confirm()).isTrue();              // PaymentAuthorized overtook InventoryReserved
        assertThat(order.markInventoryReserved()).isFalse();   // ...and the late InventoryReserved changes nothing
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void cancelledOrderCannotBeConfirmed() {
        var order = newOrder();
        order.cancel("no stock");
        assertThat(order.confirm()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelRecordsReasonAndIsTerminal() {
        var order = newOrder();
        assertThat(order.cancel("no stock")).isTrue();
        assertThat(order.getCancelReason()).isEqualTo("no stock");
        assertThat(order.cancel("again")).isFalse();
        assertThat(order.confirm()).isFalse();
        assertThat(order.markInventoryReserved()).isFalse();
    }

    @Test
    void confirmedOrderCannotBeCancelledByLateFailureEvent() {
        var order = newOrder();
        order.markInventoryReserved();
        order.confirm();
        assertThat(order.cancel("late")).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void refundSagaHappyPath() {
        var order = confirmed();
        assertThat(order.requestCancellation("Cancelled by customer")).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_PENDING);
        assertThat(order.completeCancellation()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("Cancelled by customer");
    }

    @Test
    void onlyConfirmedOrdersCanRequestCancellation() {
        var pending = newOrder();
        assertThat(pending.requestCancellation("x")).isFalse();
        var reserved = newOrder();
        reserved.markInventoryReserved();
        assertThat(reserved.requestCancellation("x")).isFalse();
        var cancelled = newOrder();
        cancelled.cancel("x");
        assertThat(cancelled.requestCancellation("x")).isFalse();
    }

    @Test
    void refusedRefundRestoresConfirmedAndClearsReason() {
        var order = confirmed();
        order.requestCancellation("changed my mind");

        assertThat(order.revertCancellation()).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getCancelReason()).isNull();
    }

    @Test
    void duplicateRefundEventsAreNoOps() {
        var order = confirmed();
        assertThat(order.completeCancellation()).isFalse();
        assertThat(order.revertCancellation()).isFalse();
        order.requestCancellation("x");
        order.completeCancellation();
        assertThat(order.completeCancellation()).isFalse();
    }

    @Test
    void lateFailureEventCannotCancelAnOrderWhoseRefundIsInFlight() {
        var order = confirmed();
        order.requestCancellation("x");
        assertThat(order.cancel("late payment failure")).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_PENDING);
    }

    private Order confirmed() {
        var order = newOrder();
        order.markInventoryReserved();
        order.confirm();
        return order;
    }
}
