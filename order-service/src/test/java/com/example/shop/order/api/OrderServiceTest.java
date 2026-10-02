package com.example.shop.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.Topics;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orders;
    @Mock OutboxService outbox;

    @Test
    void placeSavesOrderAndPublishesOrderCreatedWithTotal() {
        var service = new OrderService(orders, outbox);
        var request = new PlaceOrderRequest("cust-1", List.of(
                new PlaceOrderRequest.Item("SKU-1", 3, new BigDecimal("2.50"))));

        OrderView view = service.place(request);

        verify(orders).save(any(Order.class));
        var captor = ArgumentCaptor.forClass(OrderCreated.class);
        verify(outbox).publish(eq(Topics.ORDER_CREATED), captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(view.id());
        assertThat(captor.getValue().totalAmount()).isEqualByComparingTo("7.50");
        assertThat(captor.getValue().items()).containsExactly(new OrderCreated.Line("SKU-1", 3));
        assertThat(view.status().name()).isEqualTo("PENDING");
    }

    @Test
    void getThrowsNotFound() {
        var id = UUID.randomUUID();
        when(orders.findWithItemsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new OrderService(orders, outbox).get(id)).isInstanceOf(OrderNotFoundException.class);
    }
}
