package com.example.shop.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** outbox.pending > 0 for long means Kafka is unreachable or the relay is stuck: the number to alert on. */
@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository repository;

    public OutboxMetrics(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Events written to the outbox but not yet published to Kafka").register(registry);
    }
}
