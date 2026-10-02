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
    void cannotConfirmBeforeInventoryIsReserved() {
        var order = newOrder();
        assertThat(order.confirm()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
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
}
