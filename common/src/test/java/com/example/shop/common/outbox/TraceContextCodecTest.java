package com.example.shop.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings({"unchecked", "rawtypes"})
class TraceContextCodecTest {

    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    Tracer tracer = mock(Tracer.class);
    Propagator propagator = mock(Propagator.class);

    private <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private TraceContextCodec codec() {
        return new TraceContextCodec(provider(tracer), provider(propagator));
    }

    @Test
    void capturesTheActiveSpanAsATraceparent() {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        doAnswer(i -> {
            ((Propagator.Setter) i.getArgument(2)).set(i.getArgument(1), "traceparent", TRACEPARENT);
            return null;
        }).when(propagator).inject(any(), any(), any());

        assertThat(codec().capture()).isEqualTo(TRACEPARENT);
    }

    @Test
    void capturesNothingWithoutAnActiveSpanOrWithoutTracing() {
        when(tracer.currentSpan()).thenReturn(null);
        assertThat(codec().capture()).isNull();

        var noTracing = new TraceContextCodec(provider(null), provider(null));
        assertThat(noTracing.capture()).isNull();
        assertThat(noTracing.startRelaySpan("x", "n")).isNull();
        assertThat(noTracing.activate(null)).isNull();
    }

    @Test
    void relaySpanContinuesTheCapturedTrace() {
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);

        Span started = codec().startRelaySpan(TRACEPARENT, "outbox relay order.created.v1");

        assertThat(started).isSameAs(span);
        var carrier = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(propagator).extract(carrier.capture(), any());
        assertThat((Map<String, String>) carrier.getValue()).containsEntry("traceparent", TRACEPARENT);
        verify(builder).name("outbox relay order.created.v1");
        verify(tracer, never()).spanBuilder();
    }

    @Test
    void relaySpanStartsAFreshTraceWhenNothingWasCaptured() {
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);

        assertThat(codec().startRelaySpan(null, "n")).isSameAs(span);
        verify(propagator, never()).extract(any(), any());
    }

    @Test
    void activatingASpanMakesItCurrent() {
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.withSpan(span)).thenReturn(scope);

        assertThat(codec().activate(span)).isSameAs(scope);
    }
}
