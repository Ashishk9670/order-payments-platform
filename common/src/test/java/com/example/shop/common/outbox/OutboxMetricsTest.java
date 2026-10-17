package com.example.shop.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {

    @Test
    void pendingGaugeReflectsTheUnpublishedBacklogLive() {
        var repository = mock(OutboxRepository.class);
        var registry = new SimpleMeterRegistry();
        new OutboxMetrics(repository).bindTo(registry);

        when(repository.countByPublishedAtIsNull()).thenReturn(7L);
        assertThat(registry.get("outbox.pending").gauge().value()).isEqualTo(7.0);

        when(repository.countByPublishedAtIsNull()).thenReturn(0L);
        assertThat(registry.get("outbox.pending").gauge().value()).isZero();
    }
}
