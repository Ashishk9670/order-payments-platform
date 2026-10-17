package com.example.shop.common.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the transactional outbox. The span that was active when the event was written is serialized
 * (W3C "traceparent") into the outbox row; the relay later restores it, so the Kafka send, the consumer in another
 * service and everything it does stay in the SAME trace as the originating HTTP request. Without this the async hop
 * through the outbox would start a new, unrelated trace. A no-op when tracing is not on the classpath.
 */
@Component
public class TraceContextCodec {

    private static final String HEADER = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TraceContextCodec(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** @return the current trace context as a string, or null when there is no active span / tracing. */
    public String capture() {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || t.currentSpan() == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        p.inject(t.currentSpan().context(), carrier, Map::put);
        return carrier.get(HEADER);
    }

    /** Starts a relay span that continues the captured trace (or a fresh one if nothing was captured). */
    public Span startRelaySpan(String captured, String name) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span.Builder builder;
        if (p != null && captured != null) {
            builder = p.extract(Map.of(HEADER, captured), Map<String, String>::get);
        } else {
            builder = t.spanBuilder();
        }
        return builder.name(name).start();
    }

    public Tracer.SpanInScope activate(Span span) {
        Tracer t = tracer.getIfAvailable();
        return t == null || span == null ? null : t.withSpan(span);
    }
}
