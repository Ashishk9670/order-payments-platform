package com.example.shop.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings("unchecked")
class OutboxRelayTracingTest {

    OutboxRepository repository = mock(OutboxRepository.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    TraceContextCodec codec = mock(TraceContextCodec.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    Span span = mock(Span.class);
    Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);

    private OutboxRelay relay() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meters);
        return new OutboxRelay(repository, kafka, codec, provider);
    }

    private OutboxEvent event(String traceContext) {
        return new OutboxEvent(UUID.randomUUID(), "agg", "t1", "T", "{}").withTraceContext(traceContext);
    }

    @Test
    void eachSendRunsInASpanRestoredFromTheStoredTraceContextAndIsEnded() {
        var e = event("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(repository.lockNextBatch(100)).thenReturn(List.of(e));
        when(codec.startRelaySpan(eq(e.getTraceContext()), anyString())).thenReturn(span);
        when(codec.activate(span)).thenReturn(scope);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        relay().relay();

        var order = inOrder(codec, kafka, scope, span);
        order.verify(codec).startRelaySpan(eq(e.getTraceContext()), eq("outbox relay t1"));
        order.verify(codec).activate(span);
        order.verify(kafka).send("t1", "agg", "{}");
        order.verify(scope).close();
        order.verify(span).end();
        assertThat(meters.counter("outbox.published").count()).isEqualTo(1.0);
        assertThat(meters.counter("outbox.publish.failures").count()).isZero();
    }

    @Test
    void aFailedSendIsCountedMarksTheSpanAsErroredAndStillEndsIt() {
        var e = event(null);
        when(repository.lockNextBatch(100)).thenReturn(List.of(e));
        when(codec.startRelaySpan(any(), anyString())).thenReturn(span);
        when(codec.activate(span)).thenReturn(scope);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay().relay();

        verify(span).error(any(Throwable.class));
        verify(span).end();
        assertThat(meters.counter("outbox.publish.failures").count()).isEqualTo(1.0);
        assertThat(meters.counter("outbox.published").count()).isZero();
        assertThat(e).extracting("publishedAt").isNull();
    }
}
