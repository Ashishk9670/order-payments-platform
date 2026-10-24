package com.example.shop.order.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.domain.OrderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class StuckOrdersMonitorTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final Instant now = Instant.parse("2026-06-01T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final StuckOrdersMonitor monitor = new StuckOrdersMonitor(orders, Duration.ofMinutes(5), Duration.ofMinutes(10), clock);

    @Test
    void theGaugePerTransitionalStatusCountsOrdersOlderThanThatStatusesThreshold() {
        when(orders.countByStatusAndUpdatedAtBefore(OrderStatus.PENDING, now.minus(Duration.ofMinutes(5)))).thenReturn(3L);
        when(orders.countByStatusAndUpdatedAtBefore(OrderStatus.INVENTORY_RESERVED, now.minus(Duration.ofMinutes(5)))).thenReturn(1L);
        when(orders.countByStatusAndUpdatedAtBefore(OrderStatus.REFUND_PENDING, now.minus(Duration.ofMinutes(10)))).thenReturn(2L);
        var registry = new SimpleMeterRegistry();

        monitor.bindTo(registry);

        assertThat(registry.get("orders.stuck").tag("status", "PENDING").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("orders.stuck").tag("status", "INVENTORY_RESERVED").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("orders.stuck").tag("status", "REFUND_PENDING").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void settledStatusesAreNeverReportedAsStuck() {
        var registry = new SimpleMeterRegistry();
        monitor.bindTo(registry);

        assertThat(registry.find("orders.stuck").gauges()).extracting(g -> g.getId().getTag("status"))
                .containsExactlyInAnyOrder("PENDING", "INVENTORY_RESERVED", "REFUND_PENDING");
    }

    @Test
    void whenTheDatabaseCannotBeAskedTheGaugeIsUnknownNotZero() {
        when(orders.countByStatusAndUpdatedAtBefore(any(), any())).thenThrow(new IllegalStateException("db down"));
        var registry = new SimpleMeterRegistry();
        monitor.bindTo(registry);

        assertThat(registry.get("orders.stuck").tag("status", "PENDING").gauge().value()).isNaN();
    }

    private Order orderUpdatedAt(Instant updatedAt, OrderStatus status) throws Exception {
        var order = Order.create("cust", List.of(new Order.ItemSpec("S", 1, BigDecimal.ONE)));
        var updated = Order.class.getDeclaredField("updatedAt");
        updated.setAccessible(true);
        updated.set(order, updatedAt);
        var st = Order.class.getDeclaredField("status");
        st.setAccessible(true);
        st.set(order, status);
        return order;
    }

    @Test
    void listsStuckOrdersOldestFirstWithTheirAge() throws Exception {
        var older = orderUpdatedAt(now.minus(Duration.ofMinutes(40)), OrderStatus.PENDING);
        var newer = orderUpdatedAt(now.minus(Duration.ofMinutes(7)), OrderStatus.INVENTORY_RESERVED);
        var refund = orderUpdatedAt(now.minus(Duration.ofMinutes(20)), OrderStatus.REFUND_PENDING);
        when(orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(List.of(OrderStatus.PENDING)), any(), any(Pageable.class))).thenReturn(List.of(older));
        when(orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(List.of(OrderStatus.INVENTORY_RESERVED)), any(), any(Pageable.class))).thenReturn(List.of(newer));
        when(orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(List.of(OrderStatus.REFUND_PENDING)), any(), any(Pageable.class))).thenReturn(List.of(refund));

        var stuck = monitor.list(10);

        assertThat(stuck).extracting(StuckOrdersMonitor.StuckOrder::id).containsExactly(older.getId(), refund.getId(), newer.getId());
        assertThat(stuck.get(0).ageSeconds()).isEqualTo(40 * 60);
        assertThat(stuck.get(0).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void theListIsCappedAtTheLimit() throws Exception {
        var a = orderUpdatedAt(now.minus(Duration.ofMinutes(60)), OrderStatus.PENDING);
        var b = orderUpdatedAt(now.minus(Duration.ofMinutes(50)), OrderStatus.PENDING);
        var c = orderUpdatedAt(now.minus(Duration.ofMinutes(40)), OrderStatus.PENDING);
        when(orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(List.of(OrderStatus.PENDING)), any(), any(Pageable.class))).thenReturn(List.of(a, b, c));
        when(orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(List.of(OrderStatus.INVENTORY_RESERVED)), any(), any(Pageable.class))).thenReturn(List.of());
        when(orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(List.of(OrderStatus.REFUND_PENDING)), any(), any(Pageable.class))).thenReturn(List.of());

        assertThat(monitor.list(2)).extracting(StuckOrdersMonitor.StuckOrder::id).containsExactly(a.getId(), b.getId());
    }
}
