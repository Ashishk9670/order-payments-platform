package com.example.shop.order.reconcile;

import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.domain.OrderStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The safety net behind the sagas. Every step is retried and idempotent, so a healthy system never leaves an order half-done,
 * but "never" needs proof: this watches for orders that stay in a transitional state too long, exposes how many as the
 * <code>orders.stuck{status}</code> gauge (alerted), and lists them for operators (docs/runbooks/stuck-orders.md).
 * It reports; it does not guess at a repair, because the right fix depends on where the saga stalled.
 */
@Component
public class StuckOrdersMonitor implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(StuckOrdersMonitor.class);

    public record StuckOrder(java.util.UUID id, String customerId, OrderStatus status, long ageSeconds) {
    }

    private final OrderRepository orders;
    private final Clock clock;
    private final Map<OrderStatus, Duration> threshold;

    @org.springframework.beans.factory.annotation.Autowired
    public StuckOrdersMonitor(OrderRepository orders,
                              @Value("${orders.stuck.pending-after:PT5M}") Duration pendingAfter,
                              @Value("${orders.stuck.refund-after:PT10M}") Duration refundAfter) {
        this(orders, pendingAfter, refundAfter, Clock.systemUTC());
    }

    StuckOrdersMonitor(OrderRepository orders, Duration pendingAfter, Duration refundAfter, Clock clock) {
        this.orders = orders;
        this.clock = clock;
        this.threshold = Map.of(OrderStatus.PENDING, pendingAfter, OrderStatus.INVENTORY_RESERVED, pendingAfter,
                OrderStatus.REFUND_PENDING, refundAfter);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        threshold.forEach((status, after) -> Gauge.builder("orders.stuck", this, m -> m.count(status))
                .tag("status", status.name())
                .description("Orders that have stayed in this transitional state longer than the threshold")
                .register(registry));
    }

    double count(OrderStatus status) {
        try {
            return orders.countByStatusAndUpdatedAtBefore(status, clock.instant().minus(threshold.get(status)));
        } catch (RuntimeException e) {
            log.warn("Could not count stuck {} orders: {}", status, e.toString());
            return Double.NaN;   // "unknown" must not look like "zero"
        }
    }

    /** The oldest stuck orders, each beyond its own status's threshold. */
    @Transactional(readOnly = true)
    public List<StuckOrder> list(int limit) {
        Instant now = clock.instant();
        List<StuckOrder> out = new java.util.ArrayList<>();
        for (var entry : threshold.entrySet()) {
            for (Order o : orders.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(List.of(entry.getKey()), now.minus(entry.getValue()), PageRequest.of(0, limit))) {
                out.add(new StuckOrder(o.getId(), o.getCustomerId(), o.getStatus(), Duration.between(o.getUpdatedAt(), now).toSeconds()));
            }
        }
        out.sort(java.util.Comparator.comparingLong(StuckOrder::ageSeconds).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
