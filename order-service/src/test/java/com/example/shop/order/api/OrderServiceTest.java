package com.example.shop.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.Topics;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orders;
    @Mock OutboxService outbox;
    @Mock ApplicationEventPublisher events;
    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, outbox, events);
    }

    @Test
    void createSavesOrderAndPublishesOrderCreatedWithTotal() {
        OrderView view = service.create("cust-1", List.of(new Order.ItemSpec("SKU-1", 3, new BigDecimal("2.50"))));

        verify(orders).save(any(Order.class));
        var captor = ArgumentCaptor.forClass(OrderCreated.class);
        verify(outbox).publish(eq(Topics.ORDER_CREATED), captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(view.id());
        assertThat(captor.getValue().totalAmount()).isEqualByComparingTo("7.50");
        assertThat(captor.getValue().items()).containsExactly(new OrderCreated.Line("SKU-1", 3));
        assertThat(view.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void getThrowsNotFound() {
        var id = UUID.randomUUID();
        when(orders.findWithItemsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(OrderNotFoundException.class);
    }

    private Order confirmedOrder() {
        var order = Order.create("cust-1", List.of(new Order.ItemSpec("SKU-1", 1, BigDecimal.TEN)));
        order.markInventoryReserved();
        order.confirm();
        when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void cancellingAConfirmedOrderStartsTheRefundSaga() {
        var order = confirmedOrder();

        OrderView view = service.requestCancellation(order.getId(), " changed my mind ");

        assertThat(view.status()).isEqualTo(OrderStatus.REFUND_PENDING);
        assertThat(view.cancelReason()).isEqualTo("Cancelled by customer: changed my mind");
        var captor = ArgumentCaptor.forClass(OrderCancellationRequested.class);
        verify(outbox).publish(eq(Topics.ORDER_CANCELLATION_REQUESTED), captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(order.getId());
        verify(events).publishEvent(new OrderChanged(order.getId()));
    }

    @Test
    void blankReasonGetsADefault() {
        var order = confirmedOrder();
        assertThat(service.requestCancellation(order.getId(), "  ").cancelReason()).isEqualTo("Cancelled by customer");
    }

    @Test
    void cancellingAPendingOrderIsAConflict() {
        var order = Order.create("c", List.of(new Order.ItemSpec("S", 1, BigDecimal.ONE)));
        when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.requestCancellation(order.getId(), null))
                .isInstanceOf(OrderStateConflictException.class).hasMessageContaining("PENDING");
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void cancellingUnknownOrderIsNotFound() {
        var id = UUID.randomUUID();
        when(orders.findWithItemsById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestCancellation(id, null)).isInstanceOf(OrderNotFoundException.class);
    }
}
